-- V17: what a replaced document did to the test set and to the measured quality (SPEC 11, T2.6).
-- One row per (document, new version): the test set version the gold spans were re-located into, the regression experiment that re-ran the earlier
-- configuration on it, and the earlier run it is compared with.
CREATE TABLE change_impacts (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    document_id     uuid        NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    version         int         NOT NULL,
    status          text        NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'DONE', 'NO_QUESTIONS', 'SKIPPED', 'FAILED')),
    note            text,
    testset_id      uuid        REFERENCES testsets (id) ON DELETE SET NULL,
    experiment_id   uuid        REFERENCES experiments (id) ON DELETE SET NULL,
    previous_run_id uuid        REFERENCES runs (id) ON DELETE SET NULL,
    relocated       int         NOT NULL DEFAULT 0,
    stale           int         NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, version)
);

ALTER TABLE change_impacts ENABLE ROW LEVEL SECURITY;
ALTER TABLE change_impacts FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON change_impacts USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON change_impacts TO glassbox_app;
