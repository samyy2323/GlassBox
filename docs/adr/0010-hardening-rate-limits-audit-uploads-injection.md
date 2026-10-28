# ADR 0010: Hardening: isolation canaries, the injection suite, request limits, audit log, upload guard

Status: accepted (2026-10-29). The CLI and gate decisions are added to this record when they are built.

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

## Not done

Provider API keys are not stored by any feature yet (the table and its row-level security exist), so encrypting them with AES-GCM is not built. CSRF tokens are not used: authentication is a bearer
token or API key header, and the one cookie (refresh) is SameSite=Strict, path-scoped and needs a custom header.
