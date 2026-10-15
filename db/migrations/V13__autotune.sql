-- V13: AutoTune (P4). One row per search; one row per configuration it tries in the two retrieval-only stages.
-- The finalists of a search are measured as ordinary runs of an internal experiment (experiments.kind = 'AUTOTUNE'), so their traces,
-- run diffs and failure reports work exactly as they do for a hand-made experiment.
CREATE TABLE autotunes (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    testset_id    uuid        NOT NULL REFERENCES testsets (id) ON DELETE CASCADE,
    name          text        NOT NULL,
    objective     text        NOT NULL CHECK (objective IN ('BEST_QUALITY', 'CHEAPEST_ABOVE_FLOOR', 'FASTEST_WITHIN_CAP')),
    options       jsonb       NOT NULL DEFAULT '{}',
    status        text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    stage         text        NOT NULL DEFAULT 'QUEUED',
    split         jsonb       NOT NULL DEFAULT '{}',
    spent         jsonb       NOT NULL DEFAULT '{}',
    stopped_by    text,
    result        jsonb,
    experiment_id uuid        REFERENCES experiments (id) ON DELETE SET NULL,
    error         text,
    created_by    uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    finished_at   timestamptz
);
CREATE INDEX autotunes_ws_created_idx ON autotunes (workspace_id, created_at DESC, id DESC);

-- scores: question id -> [context recall, latency ms, prompt tokens], kept so that a round of successive halving only scores the new questions
-- and a restarted search does not repeat work.
CREATE TABLE autotune_candidates (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    autotune_id  uuid        NOT NULL REFERENCES autotunes (id) ON DELETE CASCADE,
    stage        text        NOT NULL CHECK (stage IN ('STAGE1', 'STAGE2')),
    key          text        NOT NULL,
    label        text        NOT NULL,
    config       jsonb       NOT NULL,
    settings     jsonb       NOT NULL DEFAULT '{}',
    scores       jsonb       NOT NULL DEFAULT '{}',
    final_sample int,
    UNIQUE (autotune_id, stage, key)
);

ALTER TABLE autotunes ENABLE ROW LEVEL SECURITY;
ALTER TABLE autotunes FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON autotunes
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE autotune_candidates ENABLE ROW LEVEL SECURITY;
ALTER TABLE autotune_candidates FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON autotune_candidates
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON autotunes, autotune_candidates TO glassbox_app;
