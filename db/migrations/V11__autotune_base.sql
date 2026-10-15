-- V11: building blocks for AutoTune (P4).
--  * chunks.role / parent_chunk_id: the parent_child chunker stores big PARENT chunks (never embedded, never searched) and small CHILD
--    chunks (embedded and searched). A retrieved child is swapped for its parent when the context is built. Every other chunker writes FLAT.
--  * workspace_settings: the pipeline an AutoTune run promoted to be the workspace default.
ALTER TABLE chunks ADD COLUMN role text NOT NULL DEFAULT 'FLAT' CHECK (role IN ('FLAT', 'PARENT', 'CHILD'));
ALTER TABLE chunks ADD COLUMN parent_chunk_id uuid REFERENCES chunks (id) ON DELETE SET NULL;
CREATE INDEX chunks_parent_idx ON chunks (parent_chunk_id) WHERE parent_chunk_id IS NOT NULL;

CREATE TABLE workspace_settings (
    workspace_id     uuid PRIMARY KEY REFERENCES workspaces (id) ON DELETE CASCADE,
    default_pipeline jsonb,
    updated_by       uuid,
    updated_at       timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE workspace_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspace_settings FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workspace_settings
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON workspace_settings TO glassbox_app;
