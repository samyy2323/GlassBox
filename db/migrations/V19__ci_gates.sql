-- V19: the CI quality gate (SPEC 14). A gate is an experiment started through POST /api/ci/run plus the rules it is judged by: minimum values for metrics, and the
-- largest drop against a baseline run that still passes. The verdict is worked out from the finished experiment each time it is asked for, so only the rules are stored.
CREATE TABLE ci_gates (
    experiment_id uuid PRIMARY KEY REFERENCES experiments (id) ON DELETE CASCADE,
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    spec          jsonb       NOT NULL,
    created_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ci_gates_ws_created_idx ON ci_gates (workspace_id, created_at DESC);

ALTER TABLE ci_gates ENABLE ROW LEVEL SECURITY;
ALTER TABLE ci_gates FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ci_gates USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT ON ci_gates TO glassbox_app;
