-- V2: documents, sections, chunks and the job queue (SPEC sections 4 and 5).

CREATE FUNCTION app_worker() RETURNS boolean
    LANGUAGE sql STABLE
AS $$ SELECT coalesce(current_setting('app.worker', true), '') = 'on' $$;
GRANT EXECUTE ON FUNCTION app_worker() TO glassbox_app;

CREATE TABLE documents (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    title         text        NOT NULL,
    source_type   text        NOT NULL CHECK (source_type IN ('PDF', 'DOCX', 'HTML', 'MD')),
    content_hash  text        NOT NULL,
    version       int         NOT NULL DEFAULT 1,
    status        text        NOT NULL DEFAULT 'UPLOADED'
                  CHECK (status IN ('UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'FAILED_UNSUPPORTED')),
    error         text,
    size_bytes    bigint      NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    superseded_by uuid
);
-- Exact duplicates are skipped per workspace.
CREATE UNIQUE INDEX documents_ws_hash_uq ON documents (workspace_id, content_hash);
CREATE INDEX documents_ws_created_idx ON documents (workspace_id, created_at DESC, id DESC);

-- The normalized plain text of a version is stored so that chunk offsets stay valid forever and the
-- viewer can show it without re-parsing. page_starts[i] is the offset where page i+1 begins (PDF only).
CREATE TABLE document_versions (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    document_id     uuid        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version         int         NOT NULL,
    content_hash    text        NOT NULL,
    storage_path    text        NOT NULL,
    normalized_text text,
    page_starts     int[],
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, version)
);
CREATE INDEX document_versions_ws_idx ON document_versions (workspace_id, document_id);

CREATE TABLE sections (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        uuid   NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    document_id         uuid   NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    document_version_id uuid   NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    parent_id           uuid   REFERENCES sections (id) ON DELETE CASCADE,
    heading_path        text[] NOT NULL DEFAULT '{}',
    ordinal             int    NOT NULL,
    level               int    NOT NULL DEFAULT 0,
    char_start          int    NOT NULL,
    char_end            int    NOT NULL,
    text                text   NOT NULL
);
CREATE INDEX sections_ws_doc_idx ON sections (workspace_id, document_id, ordinal);

CREATE TABLE chunkings (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid  NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    strategy     text  NOT NULL,
    params       jsonb NOT NULL,
    UNIQUE (workspace_id, strategy, params)
);

CREATE TABLE chunks (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id        uuid    NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    document_id         uuid    NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    document_version_id uuid    NOT NULL REFERENCES document_versions (id) ON DELETE CASCADE,
    section_id          uuid    REFERENCES sections (id) ON DELETE SET NULL,
    chunking_id         uuid    NOT NULL REFERENCES chunkings (id) ON DELETE CASCADE,
    ordinal             int     NOT NULL,
    -- May carry a heading-path prefix; char_start/char_end always address the original document text.
    text                text    NOT NULL,
    token_count         int     NOT NULL,
    char_start          int     NOT NULL,
    char_end            int     NOT NULL,
    page                int,
    superseded          boolean NOT NULL DEFAULT false,
    tsv                 tsvector GENERATED ALWAYS AS (to_tsvector('english', text)) STORED
);
CREATE INDEX chunks_tsv_gin ON chunks USING gin (tsv);
CREATE INDEX chunks_ws_chunking_idx ON chunks (workspace_id, chunking_id, document_id, ordinal);
CREATE INDEX chunks_doc_idx ON chunks (document_id);

CREATE TABLE jobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    type            text        NOT NULL,
    payload         jsonb       NOT NULL DEFAULT '{}',
    status          text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'DEAD')),
    attempts        int         NOT NULL DEFAULT 0,
    run_after       timestamptz NOT NULL DEFAULT now(),
    locked_by       text,
    locked_at       timestamptz,
    idempotency_key text        NOT NULL UNIQUE,
    error           text,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX jobs_queued_idx ON jobs (run_after) WHERE status = 'QUEUED';
CREATE INDEX jobs_ws_idx ON jobs (workspace_id);

-- ---------------------------------------------------------------- row-level security

ALTER TABLE documents          ENABLE ROW LEVEL SECURITY;
ALTER TABLE documents          FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON documents
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE document_versions  ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_versions  FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_versions
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE sections           ENABLE ROW LEVEL SECURITY;
ALTER TABLE sections           FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sections
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE chunkings          ENABLE ROW LEVEL SECURITY;
ALTER TABLE chunkings          FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chunkings
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE chunks             ENABLE ROW LEVEL SECURITY;
ALTER TABLE chunks             FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chunks
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

-- Workers claim jobs across workspaces. Only the queue's claim/reaper transactions set app.worker='on';
-- the actual work then runs inside the job's own workspace context. A request without that setting
-- sees only its own workspace's jobs (covered by the isolation tests).
ALTER TABLE jobs               ENABLE ROW LEVEL SECURITY;
ALTER TABLE jobs               FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_or_worker ON jobs
    USING (workspace_id = app_workspace_id() OR app_worker())
    WITH CHECK (workspace_id = app_workspace_id() OR app_worker());

GRANT SELECT, INSERT, UPDATE, DELETE ON documents, document_versions, sections, chunkings, chunks, jobs
    TO glassbox_app;
