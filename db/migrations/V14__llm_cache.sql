-- V14: cache of temperature-0 model calls, per workspace (key = hash of model, parameters and messages).
CREATE TABLE llm_cache (
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    key          text        NOT NULL,
    response     text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, key)
);

ALTER TABLE llm_cache ENABLE ROW LEVEL SECURITY;
ALTER TABLE llm_cache FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON llm_cache
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, DELETE ON llm_cache TO glassbox_app;
