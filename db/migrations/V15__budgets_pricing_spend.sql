-- V15: what a run costs and what a workspace may spend (SPEC 11, T2.8).
--  * pricing: the editable price table, per model (a model without a row uses the defaults from configuration; a local model is free).
--  * budgets: one row per workspace. A run whose estimate passes per_run_usd is refused, one whose estimate would take the month past monthly_usd is
--    refused, and one above confirm_above_usd needs an explicit confirmation.
--  * spend_log: the estimate and the actual of every finished (or aborted) run, which is where the estimator's own error is measured from.
--  * experiments get a cost cap: a run aborts when actual spend passes 120% of it.
CREATE TABLE pricing (
    workspace_id               uuid    NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    model                      text    NOT NULL,
    prompt_usd_per_million     numeric NOT NULL CHECK (prompt_usd_per_million >= 0),
    completion_usd_per_million numeric NOT NULL CHECK (completion_usd_per_million >= 0),
    PRIMARY KEY (workspace_id, model)
);

CREATE TABLE budgets (
    workspace_id      uuid PRIMARY KEY REFERENCES workspaces (id) ON DELETE CASCADE,
    monthly_usd       numeric CHECK (monthly_usd IS NULL OR monthly_usd >= 0),
    per_run_usd       numeric CHECK (per_run_usd IS NULL OR per_run_usd >= 0),
    confirm_above_usd numeric CHECK (confirm_above_usd IS NULL OR confirm_above_usd >= 0),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE spend_log (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id     uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    kind             text        NOT NULL CHECK (kind IN ('EXPERIMENT', 'AUTOTUNE')),
    ref_id           uuid        NOT NULL,
    estimated_usd    numeric     NOT NULL DEFAULT 0,
    actual_usd       numeric     NOT NULL DEFAULT 0,
    estimated_tokens bigint      NOT NULL DEFAULT 0,
    actual_tokens    bigint      NOT NULL DEFAULT 0,
    estimated_calls  int         NOT NULL DEFAULT 0,
    actual_calls     int         NOT NULL DEFAULT 0,
    aborted          boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (kind, ref_id)
);
CREATE INDEX spend_log_ws_created_idx ON spend_log (workspace_id, created_at DESC);

ALTER TABLE experiments ADD COLUMN cost_cap_usd numeric CHECK (cost_cap_usd IS NULL OR cost_cap_usd >= 0);
ALTER TABLE experiments ADD COLUMN cost_cap_calls int CHECK (cost_cap_calls IS NULL OR cost_cap_calls > 0);
ALTER TABLE experiments DROP CONSTRAINT experiments_status_check;
ALTER TABLE experiments ADD CONSTRAINT experiments_status_check CHECK (status IN ('QUEUED', 'RUNNING', 'DONE', 'FAILED', 'CANCELLED', 'ABORTED'));

ALTER TABLE pricing ENABLE ROW LEVEL SECURITY;
ALTER TABLE pricing FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pricing USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());
ALTER TABLE budgets ENABLE ROW LEVEL SECURITY;
ALTER TABLE budgets FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON budgets USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());
ALTER TABLE spend_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE spend_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON spend_log USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON pricing, budgets, spend_log TO glassbox_app;
