-- V7: test sets (SPEC 4 and 7). A test set version is DRAFT while reviewers edit it and FINAL (immutable) afterwards.
-- Editing a final version creates a new DRAFT copy with parent_version_id set; questions keep their stable_id across
-- copies so a diff can tell added, removed and edited apart. Gold evidence is stored as character spans, never chunk ids.

CREATE TABLE testsets (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id      uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name              text        NOT NULL,
    version           int         NOT NULL DEFAULT 1,
    parent_version_id uuid        REFERENCES testsets (id) ON DELETE SET NULL,
    status            text        NOT NULL CHECK (status IN ('GENERATING', 'DRAFT', 'FINAL', 'FAILED')),
    params            jsonb       NOT NULL DEFAULT '{}',
    target_count      int         NOT NULL DEFAULT 0,
    generated_count   int         NOT NULL DEFAULT 0,
    samples_done      int         NOT NULL DEFAULT 0,
    error             text,
    created_by        uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    finalized_at      timestamptz,
    UNIQUE (workspace_id, name, version)
);
CREATE INDEX testsets_ws_created_idx ON testsets (workspace_id, created_at DESC, id DESC);

CREATE TABLE test_questions (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    seq              bigint GENERATED ALWAYS AS IDENTITY,
    workspace_id     uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    testset_id       uuid        NOT NULL REFERENCES testsets (id) ON DELETE CASCADE,
    stable_id        uuid        NOT NULL DEFAULT gen_random_uuid(),
    question         text        NOT NULL,
    expected_answer  text        NOT NULL,
    gold_chunk_spans jsonb       NOT NULL DEFAULT '[]',
    qtype            text        NOT NULL CHECK (qtype IN ('FACTUAL', 'NUMERIC', 'MULTI_HOP', 'COMPARISON', 'UNANSWERABLE')),
    origin           text        NOT NULL CHECK (origin IN ('SYNTHETIC', 'MANUAL', 'FEEDBACK')),
    review_status    text        NOT NULL DEFAULT 'PENDING' CHECK (review_status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'EDITED')),
    quality_flags    text[]      NOT NULL DEFAULT '{}',
    source_trace_id  uuid        REFERENCES traces (id) ON DELETE SET NULL,
    meta             jsonb       NOT NULL DEFAULT '{}',
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (testset_id, stable_id)
);
CREATE INDEX test_questions_set_idx ON test_questions (testset_id, seq);

ALTER TABLE testsets       ENABLE ROW LEVEL SECURITY;
ALTER TABLE testsets       FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON testsets
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE test_questions ENABLE ROW LEVEL SECURITY;
ALTER TABLE test_questions FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON test_questions
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

GRANT SELECT, INSERT, UPDATE, DELETE ON testsets, test_questions TO glassbox_app;
