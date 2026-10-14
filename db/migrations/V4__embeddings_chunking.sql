-- V4: carry chunking_id on embeddings.
--
-- Vector search filters by chunking (and tenant, via RLS). pgvector applies a filter AFTER the HNSW scan, which
-- returns fewer than k rows when the filter is selective. Its iterative scan (hnsw.iterative_scan) keeps scanning
-- until enough rows pass, but only for filters evaluated in the same index-scan node. A join against chunks is
-- evaluated above the node, so the filter column has to live on embeddings itself.

ALTER TABLE embeddings ADD COLUMN chunking_id uuid REFERENCES chunkings (id) ON DELETE CASCADE;

-- Backfill as the table owner. RLS is forced on these tables, so lift it for this transaction only
-- (Flyway runs each migration in one transaction, so there is no window where it is off).
ALTER TABLE embeddings NO FORCE ROW LEVEL SECURITY;
ALTER TABLE chunks     NO FORCE ROW LEVEL SECURITY;
UPDATE embeddings e SET chunking_id = c.chunking_id FROM chunks c WHERE c.id = e.chunk_id;
ALTER TABLE embeddings FORCE ROW LEVEL SECURITY;
ALTER TABLE chunks     FORCE ROW LEVEL SECURITY;

ALTER TABLE embeddings ALTER COLUMN chunking_id SET NOT NULL;
CREATE INDEX embeddings_chunking_idx ON embeddings (workspace_id, chunking_id);
