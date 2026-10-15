# ADR 0009: Reports are frozen snapshots, and a share link opens exactly one of them

Status: accepted (2026-10-15).

A share link is the only part of the product that is reachable without signing in, so it is built so that a bug in it cannot become a way into another tenant's data.

## The snapshot

When a report is made (PDF or link), `ReportBuilder` copies what it shows out of the live tables into `reports.snapshot`: summary sentences, configurations in words, metrics with their
intervals, verdicts, the failure histogram, test set statistics and the limitations that apply (uncalibrated judges, small samples, stale questions). Nothing the report shows is read live
afterwards, so a link can never reach a tenant table, and the report does not change when the run does (tested by renaming the experiment after the report was made).

The snapshot never holds document text or chat content. It does hold the text of questions that failed, unless the report is made **redacted**, in which case it holds numbers only
(tested: the question text is in the plain PDF and absent from the redacted one).

## The link

- A random 256-bit token (32 bytes, base64url, 43 characters), shown once. Only its SHA-256 is stored. It is never written to the audit log.
- It expires (1 to 90 days, default 7) and can be revoked. A malformed, unknown, expired or revoked token all give the same 404.
- It opens one report through `GET /api/public/reports/{token}` and `/pdf`. There is no other public route, and the token is not accepted as a credential anywhere else (tested: as a bearer
  token, and as a header, on API routes).
- Responses are `Cache-Control: no-store` and `X-Robots-Tag: noindex`.

## How the database enforces it

`reports` and `report_links` keep **forced** row-level security like every tenant table (a test fails the build if one does not). The public read goes through one `SECURITY DEFINER`
function, `public_report(hash)`. It puts the hash in a transaction-local setting and selects; two narrow policies let that session see exactly one link (the one with that hash, unexpired,
unrevoked) and the report it points to. There is no tenant context on that path, so nothing else is visible. The function returns title, snapshot, creation and expiry, and no workspace id.

Residual risk, stated: a session of the application role can set that same setting itself, so SQL injection plus knowledge of a token's hash would read that one report. The hash cannot
be turned back into a token, and the same injection could read the snapshot directly, so the policy adds no power over what the snapshot already is.

## The PDF

Drawn with PDFBox's built-in fonts (no font files, same output everywhere): text, tables of numbers, a bar chart of failures by stage, and a quality-against-cost plot. Characters outside
Latin-1 are replaced with `?`. It is plain on purpose; tests extract its text and check the numbers, the section names and the redaction.
