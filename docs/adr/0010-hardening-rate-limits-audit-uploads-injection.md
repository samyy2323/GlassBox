# ADR 0010: Hardening: isolation canaries, the injection suite, request limits, audit log, upload guard

Status: accepted (2026-10-29). Covers the hardening work of P6, then observability, the quality gate, the CLI and the pull request action.

## Isolation is tested end to end, not only at the table

`RlsIsolationTest` proves the database refuses foreign rows. `CanaryLeakTest` proves the application on top of it does too: workspace B holds a canary string in its documents (including the file
name), its test questions, its feedback and a shared report, with the same vocabulary as workspace A so a search that crossed the line would find it. Workspace A then chats, opens X-Ray, runs an
experiment, makes a report, opens its public link and lists everything it can; the canary must not be in any response body, PDF text, snapshot or log line (a Logback list appender captures the log),
and B's identifiers must answer 403 or 404. The test first checks that B can see its own canary, so it cannot pass by looking at nothing.

Two further guards in `RlsIsolationTest`: the three `SECURITY DEFINER` functions (`auth_find_user`, `auth_create_user`, `public_report`) are listed by name, so a new one fails the build until it
is reviewed, and each must pin its `search_path`; and `audit_log` is append-only for the application role (UPDATE, DELETE and TRUNCATE are permission errors).

## Prompt injection: two layers, measured separately

The model sees only chunks retrieved under row-level security and has no tools, so another tenant's text can never be in the prompt. What a document can still do is change what an answer
*says*. The suite (`benchmarks/injection/attacks.json`, 30 attacks in 9 categories) is used twice:

1. **Deterministic** (`InjectionSuiteTest`, in the build). The model is a fake that *obeys* whatever the document says. What is asserted is everything around it: the hostile text stays inside
   one `<context>` fence (a `</context>` in a document is neutralised), no final answer contains a link, image, tag or script, and no other workspace's canary reaches the prompt. Both
   defenses were checked by breaking them: turning off the fence neutralising or the URL stripping makes the test fail.
2. **Measured** (`benchmarks/injection_suite.py`, live). The same attacks against `llama3.2:3b`. Attack success is reported per category in the README, including the ones that work.

A text-only manipulation (the document says "answer PWNED") cannot be stopped by an output filter, and the suite counts it instead of pretending otherwise. In-tenant users can still write
documents that change answers; the claim checker and the citations are what let a reader notice.

## Request limits

A token bucket per caller on every `/api/**` request (`ApiRateLimitFilter`, after authentication):

- sign-in, sign-up and refresh, and the public report links: per client address (nothing is authenticated yet, and these are what gets guessed or flooded);
- a signed-in user or an API key: per user or per key; API keys also per workspace, because the workspace of a key comes from the key;
- requests with no valid credential: per client address, so a flood of 401s is bounded too.

The `X-Workspace-Id` header of a signed-in user is **not** used as a limiter key: it is not verified at that point, and counting it would let one tenant use up another tenant's allowance (tested).
The client address is Tomcat's remote address; behind the web container's nginx it comes from `X-Forwarded-For`, which Tomcat honours only from private-range proxies. Buckets are in memory per
process, which is right for one API node and wrong for several (the limit would be multiplied by the node count): moving them into the database is the change to make before running more than one.
Chat keeps its own, tighter limit and the single-generation gate. At most `glassbox.limits.max-active-runs` (3) experiments and AutoTune searches of a workspace may be queued or running at once;
the next gets 409 with the numbers.

## Audit log

Sensitive actions already wrote `audit_log` rows (members, API keys, uploads and deletions, test set finalizing, experiments, AutoTune promotion, budgets, report creation, links and revocation).
Added: price deletion, `GET /api/audit` (owners and admin-scoped keys; newest first; filter by action prefix; paged by `before`), and tests that each action writes exactly one row with the actor and
that the log never holds an API key or a link token. The table is append-only for the application role, so a compromised application cannot rewrite history; the database owner can.

## Uploads

Size (25 MB), type sniffing and a 60 s parser timeout existed. Added `ArchiveGuard` before any parser opens a zip-based file (Office, OpenDocument, EPUB): it really inflates the bytes and counts
them (the sizes in a zip header can be forged), and refuses more than 5,000 parts, more than 256 MB expanded, an overall expansion of more than 200 times once past 10 MB, and an archive inside
the archive. A 40 MB file of zeros compresses to under 100 KB and is refused with 422 without being stored or queued.

## Headers and CORS

CSP `default-src 'none'`, `frame-ancestors 'none'`, nosniff, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, a `Permissions-Policy` that turns off camera, microphone, geolocation,
payment and USB, no-store caching, and HSTS on HTTPS requests; tested on 200, 400, 401 and public-route answers. CORS allows only the configured origin and the headers the API uses (tested:
a foreign origin and an unknown header are refused). Dependabot covers Maven, npm, pip, Docker and GitHub Actions; secrets and vulnerable dependencies are scanned on every push by the Trivy job
(a separate gitleaks job was not added, as Trivy's secret scanner already runs).

## Not done (hardening)

Provider API keys are not stored by any feature yet (the table and its row-level security exist), so encrypting them with AES-GCM is not built. CSRF tokens are not used: authentication is a bearer
token or API key header, and the one cookie (refresh) is SameSite=Strict, path-scoped and needs a custom header.

## Observability

Metrics named in the SPEC are all exported: `ingest_queue_depth` and `experiment_runs_active` (gauges read from the database when scraped and cached for five seconds, counted across
all workspaces because they describe the system), `ingest_duration` and `retrieval_stage_duration{stage}` (histograms), `llm_tokens_total{provider,model,direction}`,
`llm_cost_usd_total`, `job_retries_total`, `rls_context_missing_total`, plus `rate_limited_total{scope}` and the HTTP server latency buckets. No metric carries a workspace id as a label
(tested): a label per tenant would be unbounded and would put tenant ids on a scrape endpoint. Tokens are counted in the project's own units, which run below a real tokenizer's count.

Logs are one JSON object per line (Spring Boot's logstash format, switched on in the compose file by `LOGGING_STRUCTURED_FORMAT_CONSOLE`) carrying `traceId` and `spanId` from the
OpenTelemetry bridge and `workspace_id`. The workspace comes from the one place it is verified, the transaction wrapper `Tx`; the per-request log line (`RequestLogFilter`) adds it only when
the request succeeded, because only then has the header been checked against the caller's memberships. It never logs a query string, header or body, and replaces the token of a share link
in its path. Nothing is exported over OTLP until an endpoint is configured. `/actuator/prometheus` is open on the API port for the scraper and is not routed by the web container's proxy;
a deployment should keep that port off the internet.

The dashboard (`deploy/grafana/dashboards/glassbox.json`) and the alert rules (`deploy/prometheus/alerts.yml`, including queue depth above 50 for ten minutes) are checked by a test that
extracts every metric name they use and requires the application to export it; that test found that the HTTP latency panel would have been empty because Spring publishes no buckets for
`http.server.requests` unless asked. `docker compose --profile observability up` starts Prometheus and Grafana. The OpenAPI description (springdoc, `/api/docs/openapi`, behind sign-in)
is checked against the router: every route the application serves must be in it.

## The quality gate

`POST /api/ci/run` starts an ordinary experiment (kind REGRESSION, one configuration) and stores the rules it is judged by; `GET /api/ci/runs/{id}` works the verdict out from the finished
experiment each time, as `json`, `md` (a table for a pull request comment) or `junit`. Because it is an experiment, the budget, the cap on concurrent runs, the queue, the audit log and the
isolation tests apply without gate-specific code.

- **Minimum**: the metric must be at least the given fraction. The measured value is compared by default; with `strict`, the lower end of its 95% interval, so a pass means "confidently at least".
- **Regression** (needs a baseline run on the same test set version): the gate fails **only when the drop is larger than the allowed drop and statistically significant** (the Eval Lab's paired
  test, p below `alpha`, default 0.05). A large drop that cannot be told from noise is reported and does not block, because a gate that blocks on noise gets switched off. An improvement or no
  change passes. A missing comparison fails; it is never a silent pass.
- **States**: PASSED, FAILED (a rule broke), ERROR (the run failed, was cancelled, or the baseline cannot be compared) and RUNNING. The CLI maps them to exit codes 0, 1, 2 and 2, so a broken
  gate is never mistaken for a failed one or for a pass. A gate with no checks passes nothing.
- A key needs the `run` scope to start a gate and any scope to read one. Text that reaches the Markdown table or the XML (metric titles, reasons) is escaped.

Test sets move between workspaces as JSON lines (`GET /api/testsets/{id}/export`, `POST /api/testsets/import`): each question carries the title of its document and the quoted evidence,
not ids. On import the quote is found again in the current text of the document with that title (exact, then re-spaced, then a passage sharing 75% of its words, the same matching as a
replaced document), and a question whose evidence cannot be found is reported and left out, never kept with a guess.

## The CLI and the pull request action

`cli/` is a small picocli program (`glassbox login | run | testset export|import | report`) over the HTTP API, one jar. Exit codes: 0 passed, 1 gate failed, 2 error (usage, no sign-in,
network, server refusal). `GLASSBOX_SERVER` and `GLASSBOX_API_KEY` take precedence over the saved file, so CI needs no login step and the key never appears on a command line; the file is
readable by its owner only. Output for people goes to standard output and progress to standard error, so `glassbox run > summary.md` works.

`.github/actions/glassbox-gate` runs the CLI and keeps **one** comment on the pull request up to date. Its inputs reach scripts only through environment variables. A comment that cannot be
written (a fork's read-only token) never changes the result. `.github/workflows/gate.yml` gates Glassbox's own retrieval code: it starts the stack in the runner with throwaway secrets, loads
the frozen documents (`benchmarks/frozen/docs`) and the frozen test set (81 questions), measures the code against the minimums in `benchmarks/frozen/gate.json`, and then runs a
deliberately bad configuration that **must** exit 1; if it does not, the job fails because the gate is broken.

## Not done

Rate-limit buckets are per process. The gate API does not yet put its result in the audit log beyond the start of the run. A baseline for the pull request's own gate would need the base branch
built and measured in the same job; the frozen gate uses absolute minimums instead. The pull request action has been exercised through the CLI it wraps and the same commands in the
workflow, not by GitHub itself until the workflow has run once on a pull request or by hand.
