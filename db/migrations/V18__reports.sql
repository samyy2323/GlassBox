-- V18: shareable reports (SPEC 11, T2.10).
--  * reports: a frozen snapshot of an experiment or AutoTune run (numbers, intervals, verdicts, failure histogram, limitations). The snapshot is the ONLY
--    thing a share link can ever show: it is copied out of the live tables when the report is made, so a link never reaches tenant data.
--  * report_links: a link is a random 256-bit token that is shown once; only its SHA-256 is stored. It has an expiry and can be revoked.
--  * public_report(hash): the single way the unauthenticated endpoint reads anything. It is SECURITY DEFINER (like the login functions of V1) and returns
--    one snapshot for a valid, unexpired, unrevoked token hash and nothing else. Row-level security stays FORCEd on both tables; the two policies below
--    are what let that one function see one row.
CREATE TABLE reports (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    kind         text        NOT NULL CHECK (kind IN ('EXPERIMENT', 'AUTOTUNE')),
    ref_id       uuid        NOT NULL,
    title        text        NOT NULL,
    snapshot     jsonb       NOT NULL,
    redacted     boolean     NOT NULL DEFAULT false,
    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX reports_ws_created_idx ON reports (workspace_id, created_at DESC);

CREATE TABLE report_links (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    report_id    uuid        NOT NULL REFERENCES reports (id) ON DELETE CASCADE,
    token_hash   text        NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    revoked_at   timestamptz,
    created_by   uuid        REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE reports FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reports USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());
ALTER TABLE report_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE report_links FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON report_links USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

-- The public read, still under forced row-level security: with no tenant context the only visible link is the one whose hash public_report() has just put
-- in app.report_token_hash (and only while it is unexpired and unrevoked), and the only visible report is the one that link points at.
CREATE POLICY public_link ON report_links FOR SELECT
    USING (token_hash = nullif(current_setting('app.report_token_hash', true), '') AND revoked_at IS NULL AND expires_at > now());
CREATE POLICY public_report ON reports FOR SELECT
    USING (EXISTS (SELECT 1 FROM report_links l WHERE l.report_id = reports.id));

CREATE FUNCTION public_report(p_hash text)
    RETURNS TABLE (title text, snapshot jsonb, created_at timestamptz, expires_at timestamptz)
    LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp
AS $$
BEGIN
    PERFORM set_config('app.report_token_hash', p_hash, true);
    RETURN QUERY SELECT r.title, r.snapshot, r.created_at, l.expires_at
                 FROM report_links l JOIN reports r ON r.id = l.report_id
                 WHERE l.token_hash = p_hash AND l.revoked_at IS NULL AND l.expires_at > now();
    PERFORM set_config('app.report_token_hash', '', true);
END $$;

REVOKE ALL ON FUNCTION public_report(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public_report(text) TO glassbox_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON reports, report_links TO glassbox_app;
