-- V3: embeddings with one HNSW index per embedding model (SPEC section 4).
--
-- The vector column is dimensionless so that models of different sizes share one table. pgvector can only
-- index a fixed dimension, so each model gets a partial index on the cast expression; queries must repeat
-- the exact expression `vector::vector(N)` and the `model = '...'` predicate to use it.
-- Indexes are created here (by the table owner) for the models Glassbox supports, because the runtime
-- role cannot create indexes. Adding a model means adding an index in a later migration.

CREATE TABLE embeddings (
    chunk_id     uuid   NOT NULL REFERENCES chunks (id) ON DELETE CASCADE,
    workspace_id uuid   NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    model        text   NOT NULL,
    dim          int    NOT NULL,
    vector       vector NOT NULL,
    content_hash text   NOT NULL,
    PRIMARY KEY (chunk_id, model),
    CHECK (vector_dims(vector) = dim)
);
-- Reuse lookup: same text already embedded with this model in this workspace.
CREATE INDEX embeddings_reuse_idx ON embeddings (workspace_id, model, content_hash);

CREATE INDEX embeddings_hnsw_bge_small ON embeddings USING hnsw ((vector::vector(384)) vector_cosine_ops)
    WITH (m = 16, ef_construction = 64) WHERE model = 'bge-small-en-v1.5';
CREATE INDEX embeddings_hnsw_bge_base ON embeddings USING hnsw ((vector::vector(768)) vector_cosine_ops)
    WITH (m = 16, ef_construction = 64) WHERE model = 'bge-base-en-v1.5';
CREATE INDEX embeddings_hnsw_minilm ON embeddings USING hnsw ((vector::vector(384)) vector_cosine_ops)
    WITH (m = 16, ef_construction = 64) WHERE model = 'all-MiniLM-L6-v2';
CREATE INDEX embeddings_hnsw_openai_small ON embeddings USING hnsw ((vector::vector(1536)) vector_cosine_ops)
    WITH (m = 16, ef_construction = 64) WHERE model = 'text-embedding-3-small';

ALTER TABLE embeddings ENABLE ROW LEVEL SECURITY;
ALTER TABLE embeddings FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON embeddings
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON embeddings TO glassbox_app;
