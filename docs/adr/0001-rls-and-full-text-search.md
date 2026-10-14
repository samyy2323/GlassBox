# ADR 0001: Row-level security and full-text search

Status: accepted (2026-10-14). Measured on PostgreSQL 16 with pgvector 0.8.

## Context

SPEC 6 asks us to confirm with `EXPLAIN` that the HNSW and GIN indexes are used with tenant RLS switched on.
Every tenant table has forced RLS, and the retrieval SQL runs as the non-owner `glassbox_app`.

## Findings

| Branch | Index | Used under RLS? | Why |
| --- | --- | --- | --- |
| Vector | partial HNSW on `embeddings` | **Yes** | The distance operator is in `ORDER BY`, not a filter, so the leakproof rule does not apply. |
| Keyword | GIN on `chunks.tsv` | **No** | `ts_match_vq` (the `@@` operator) is not marked `LEAKPROOF` (`pg_proc.proleakproof = f`). Postgres refuses to evaluate a non-leakproof user qual before a row-security policy, so it cannot be an index condition. Without RLS (superuser) the same query uses `chunks_tsv_gin`. |

Under RLS the keyword branch therefore runs as: btree index scan on `(workspace_id, chunking_id, ...)`, then a filter on
`tsv @@ query`, then a sort by `ts_rank_cd`. It is bounded to one tenant's chunks of one chunking, never the whole table.

Both facts are pinned by `HybridRetrieverTest.queryPlanUsesTheHnswIndexAndTheKeywordIndexIsUsable`.

A second, related finding: pgvector applies filters after the HNSW scan, so a selective filter can return fewer than k rows.
Two things prevent it: the tenant and chunking filters live on `embeddings` itself (migration V4), so they are evaluated inside
the index-scan node, and every retrieval sets `hnsw.iterative_scan = relaxed_order`.
`filteredHnswSearchReturnsKRowsOnlyBecauseOfIterativeScan` shows 0 of 20 rows without it and 20 of 20 with it.

## Decision

Accept the tenant-bounded scan for keyword search. For the target corpora (tens of documents, thousands of chunks per
tenant) it costs milliseconds. Benchmark B11 will report keyword latency under RLS as the corpus grows.

## Alternatives considered

- `ALTER FUNCTION ts_match_vq(tsvector, tsquery) LEAKPROOF`: restores GIN use, but needs superuser, edits a system catalog entry,
  and is impossible on most managed Postgres. Possible as an opt-in hardening step for self-hosted deployments that outgrow the scan.
- Partitioning `chunks` by workspace: does not remove the leakproof restriction.
- Dropping RLS for the keyword branch: rejected; tenant isolation must not depend on application code.
