-- V9: full experiment runs (generation, claim verification, judging) and judge calibration (SPEC 7 and 8).

-- Everything needed to reproduce or question a run: the generation model, judge model, rubric and prompt versions, seed, temperature.
ALTER TABLE runs ADD COLUMN meta jsonb NOT NULL DEFAULT '{}';

-- One row per item a judge gave a verdict on (one claim for faithfulness, one answer for correctness or relevance), kept so a human
-- can label a sample without seeing the judge's verdict and the two can then be compared.
CREATE TABLE judge_labels (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    run_result_id  uuid        NOT NULL REFERENCES run_results (id) ON DELETE CASCADE,
    metric         text        NOT NULL CHECK (metric IN ('faithfulness', 'correctness', 'relevance')),
    item_key       text        NOT NULL,
    judge_model    text        NOT NULL,
    rubric_version text        NOT NULL,
    judge_label    text        NOT NULL,
    human_label    text,
    labeled_by     uuid        REFERENCES users (id) ON DELETE SET NULL,
    labeled_at     timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_result_id, metric, item_key, judge_model, rubric_version)
);
CREATE INDEX judge_labels_ws_idx ON judge_labels (workspace_id, judge_model, rubric_version, metric);

-- The latest agreement between a judge (model and rubric version) and the human labels, per metric.
CREATE TABLE judge_calibrations (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id   uuid             NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    judge_model    text             NOT NULL,
    rubric_version text             NOT NULL,
    metric         text             NOT NULL CHECK (metric IN ('faithfulness', 'correctness', 'relevance')),
    n              int              NOT NULL,
    agreement      double precision NOT NULL,
    kappa          double precision NOT NULL,
    confusion      jsonb            NOT NULL DEFAULT '{}',
    updated_at     timestamptz      NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, judge_model, rubric_version, metric)
);

ALTER TABLE judge_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE judge_labels FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON judge_labels
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE judge_calibrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE judge_calibrations FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON judge_calibrations
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON judge_labels, judge_calibrations TO glassbox_app;
