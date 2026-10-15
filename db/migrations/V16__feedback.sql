-- V16: production feedback (SPEC 11, T1.3). A thumbs-down on a chat answer lands here with a copy of the question and the answer, so the inbox still
-- reads after the trace has been purged by the retention job (trace_id is then cleared, not the feedback).
CREATE TABLE feedback (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    trace_id              uuid        REFERENCES traces (id) ON DELETE SET NULL,
    question              text        NOT NULL,
    answer                text,
    rating                text        NOT NULL CHECK (rating IN ('UP', 'DOWN')),
    comment               text,
    status                text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CONVERTED', 'DISMISSED')),
    converted_question_id uuid        REFERENCES test_questions (id) ON DELETE SET NULL,
    created_by            uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);
-- one rating per person per answer; rating again changes it
CREATE UNIQUE INDEX feedback_trace_user_uq ON feedback (trace_id, created_by) WHERE trace_id IS NOT NULL;
CREATE INDEX feedback_ws_status_idx ON feedback (workspace_id, status, created_at DESC);

ALTER TABLE feedback ENABLE ROW LEVEL SECURITY;
ALTER TABLE feedback FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON feedback USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON feedback TO glassbox_app;
