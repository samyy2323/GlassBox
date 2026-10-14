-- V8: experiments (SPEC 4 and 7). An experiment is one FINAL test set version plus a list of pipeline configurations; each
-- configuration becomes a run, each run holds one result per question, and statistics are computed from those per-question scores.

CREATE TABLE pipeline_configs (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    config       jsonb       NOT NULL,
    config_hash  text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, config_hash)
);

CREATE TABLE experiments (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    testset_id     uuid        NOT NULL REFERENCES testsets (id) ON DELETE CASCADE,
    name           text        NOT NULL,
    status         text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    kind           text        NOT NULL DEFAULT 'MANUAL' CHECK (kind IN ('MANUAL', 'AUTOTUNE', 'REGRESSION')),
    mode           text        NOT NULL DEFAULT 'RETRIEVAL' CHECK (mode IN ('RETRIEVAL', 'FULL')),
    -- questions that can be measured (accepted, with gold evidence) and those left out because their document changed since the test set was written
    question_count int         NOT NULL DEFAULT 0,
    stale_count    int         NOT NULL DEFAULT 0,
    estimated_cost jsonb       NOT NULL DEFAULT '{}',
    actual_cost    jsonb       NOT NULL DEFAULT '{}',
    error          text,
    created_by     uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    finished_at    timestamptz
);
CREATE INDEX experiments_ws_created_idx ON experiments (workspace_id, created_at DESC, id DESC);

CREATE TABLE runs (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    experiment_id uuid        NOT NULL REFERENCES experiments (id) ON DELETE CASCADE,
    config_id     uuid        NOT NULL REFERENCES pipeline_configs (id) ON DELETE RESTRICT,
    label         text        NOT NULL,
    ordinal       int         NOT NULL,
    status        text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED')),
    samples_done  int         NOT NULL DEFAULT 0,
    started_at    timestamptz,
    finished_at   timestamptz,
    UNIQUE (experiment_id, ordinal)
);

CREATE TABLE run_results (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    run_id        uuid        NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    question_id   uuid        NOT NULL REFERENCES test_questions (id) ON DELETE CASCADE,
    retrieved     jsonb       NOT NULL DEFAULT '[]',
    final_context jsonb       NOT NULL DEFAULT '[]',
    answer        text,
    claims        jsonb,
    metrics       jsonb       NOT NULL DEFAULT '{}',
    failure_stage text,
    latency_ms    int,
    tokens_in     int,
    tokens_out    int,
    cost_usd      numeric(12, 6) NOT NULL DEFAULT 0,
    trace_id      uuid        REFERENCES traces (id) ON DELETE SET NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    -- idempotent per (run, question): a crashed run resumes, a repeated delivery cannot double-count
    UNIQUE (run_id, question_id)
);
CREATE INDEX run_results_run_idx ON run_results (run_id);

CREATE TABLE run_metrics (
    run_id       uuid             NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    workspace_id uuid             NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    metric       text             NOT NULL,
    slice        text             NOT NULL DEFAULT 'all',
    method       text             NOT NULL DEFAULT 'question' CHECK (method IN ('question', 'cluster')),
    value        double precision,
    ci_low       double precision,
    ci_high      double precision,
    n            int              NOT NULL,
    PRIMARY KEY (run_id, metric, slice, method)
);

CREATE TABLE comparisons (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid             NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    run_a        uuid             NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    run_b        uuid             NOT NULL REFERENCES runs (id) ON DELETE CASCADE,
    metric       text             NOT NULL,
    delta        double precision NOT NULL,
    ci_low       double precision,
    ci_high      double precision,
    p_value      double precision,
    p_adjusted   double precision,
    verdict      text             NOT NULL,
    created_at   timestamptz      NOT NULL DEFAULT now(),
    UNIQUE (run_a, run_b, metric)
);

ALTER TABLE pipeline_configs ENABLE ROW LEVEL SECURITY;
ALTER TABLE pipeline_configs FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pipeline_configs
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE experiments ENABLE ROW LEVEL SECURITY;
ALTER TABLE experiments FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON experiments
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE runs FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON runs
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE run_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_results FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON run_results
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE run_metrics ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_metrics FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON run_metrics
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE comparisons ENABLE ROW LEVEL SECURITY;
ALTER TABLE comparisons FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON comparisons
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON pipeline_configs, experiments, runs, run_results, run_metrics, comparisons TO glassbox_app;
