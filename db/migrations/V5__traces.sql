-- V5: persisted traces (SPEC 4 and 10). A trace stores everything X-Ray needs to explain one answer:
-- spans (timings per stage) and data (candidates with ranks and scores, what entered the prompt and what was dropped,
-- the exact prompt, citations). Chat answers write one; experiment runs (P3) will too.

CREATE TABLE traces (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    kind         text        NOT NULL CHECK (kind IN ('CHAT', 'EXPERIMENT')),
    status       text        NOT NULL DEFAULT 'OK' CHECK (status IN ('OK', 'ABSTAINED', 'ERROR', 'CANCELLED')),
    question     text        NOT NULL,
    answer       text,
    model        text,
    latency_ms   int,
    spans        jsonb       NOT NULL DEFAULT '[]',
    data         jsonb       NOT NULL DEFAULT '{}',
    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX traces_ws_created_idx ON traces (workspace_id, created_at DESC, id DESC);

ALTER TABLE traces ENABLE ROW LEVEL SECURITY;
ALTER TABLE traces FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON traces
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, DELETE ON traces TO glassbox_app;
