-- V1: tenancy, auth and audit tables with Row-Level Security (SPEC sections 4 and 12).
--
-- Runs as glassbox_owner. The runtime role glassbox_app must already exist (created by
-- deploy/db/init/01-roles.sh, or by the test harness); it owns nothing and has no BYPASSRLS.
-- Extensions (vector) are installed by the init script because they need a superuser.

-- Request context. The API runs SET LOCAL app.workspace_id / app.user_id at the start of each
-- transaction. After the transaction ends the setting reads back as '' (not NULL), so nullif()
-- is required, otherwise ''::uuid would raise instead of failing closed.
CREATE FUNCTION app_workspace_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.workspace_id', true), '')::uuid $$;

CREATE FUNCTION app_user_id() RETURNS uuid
    LANGUAGE sql STABLE
AS $$ SELECT nullif(current_setting('app.user_id', true), '')::uuid $$;

GRANT EXECUTE ON FUNCTION app_workspace_id(), app_user_id() TO glassbox_app;

-- ---------------------------------------------------------------- tables

CREATE TABLE workspaces (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         text        NOT NULL,
    password_hash text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));

CREATE TABLE memberships (
    workspace_id uuid NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         text NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX memberships_user_idx ON memberships (user_id);

CREATE TABLE api_keys (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    key_hash     text        NOT NULL UNIQUE,
    label        text        NOT NULL,
    scopes       text[]      NOT NULL DEFAULT '{read}',
    created_at   timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz
);
CREATE INDEX api_keys_workspace_idx ON api_keys (workspace_id);

CREATE TABLE provider_keys (
    workspace_id  uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    provider      text        NOT NULL,
    encrypted_key bytea       NOT NULL,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, provider)
);

CREATE TABLE audit_log (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id uuid        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    actor        text        NOT NULL,
    action       text        NOT NULL,
    entity       text,
    detail       jsonb       NOT NULL DEFAULT '{}',
    at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX audit_log_workspace_at_idx ON audit_log (workspace_id, at DESC);

-- Refresh tokens are user-scoped, not workspace-scoped. The cookie value is "<userId>.<secret>",
-- so the service sets app.user_id before lookup and the policy below applies.
CREATE TABLE refresh_tokens (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  text        NOT NULL UNIQUE,
    expires_at  timestamptz NOT NULL,
    revoked_at  timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

-- ---------------------------------------------------------------- row-level security

-- Tenant tables: every row carries workspace_id; no context means no rows (fails closed).
ALTER TABLE api_keys      ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_keys      FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON api_keys
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE provider_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE provider_keys FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON provider_keys
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

ALTER TABLE audit_log     ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log     FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());

-- memberships: visible inside the current workspace, plus a user's own rows (to list "my workspaces").
-- Writes only inside the current workspace; the service layer checks the caller is OWNER first.
ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE  ROW LEVEL SECURITY;
CREATE POLICY membership_select ON memberships FOR SELECT
    USING (workspace_id = app_workspace_id() OR user_id = app_user_id());
CREATE POLICY membership_write ON memberships FOR INSERT
    WITH CHECK (workspace_id = app_workspace_id());
CREATE POLICY membership_update ON memberships FOR UPDATE
    USING (workspace_id = app_workspace_id()) WITH CHECK (workspace_id = app_workspace_id());
CREATE POLICY membership_delete ON memberships FOR DELETE
    USING (workspace_id = app_workspace_id());

-- workspaces: the workspace itself is the tenant row (id plays the role of workspace_id).
-- Visible when it is the current workspace or the current user is a member. Created with the
-- context set to the new id. memberships has its own policy and does not reference workspaces,
-- so the sub-select cannot recurse.
ALTER TABLE workspaces ENABLE ROW LEVEL SECURITY;
ALTER TABLE workspaces FORCE  ROW LEVEL SECURITY;
CREATE POLICY workspace_select ON workspaces FOR SELECT
    USING (id = app_workspace_id()
           OR EXISTS (SELECT 1 FROM memberships m
                      WHERE m.workspace_id = workspaces.id AND m.user_id = app_user_id()));
CREATE POLICY workspace_insert ON workspaces FOR INSERT
    WITH CHECK (id = app_workspace_id() AND app_user_id() IS NOT NULL);
CREATE POLICY workspace_update ON workspaces FOR UPDATE
    USING (id = app_workspace_id()) WITH CHECK (id = app_workspace_id());
CREATE POLICY workspace_delete ON workspaces FOR DELETE
    USING (id = app_workspace_id());

-- users has no workspace_id. A user sees themself and fellow members of the current workspace.
-- Not FORCEd on purpose: the SECURITY DEFINER functions below (owned by glassbox_owner) must reach
-- rows before any user context exists (login, registration). glassbox_app is not the owner, so the
-- policies always apply to it.
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_self_or_member ON users FOR SELECT
    USING (id = app_user_id()
           OR id IN (SELECT m.user_id FROM memberships m WHERE m.workspace_id = app_workspace_id()));
CREATE POLICY user_self_update ON users FOR UPDATE
    USING (id = app_user_id()) WITH CHECK (id = app_user_id());

ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens FORCE  ROW LEVEL SECURITY;
CREATE POLICY own_tokens ON refresh_tokens
    USING (user_id = app_user_id()) WITH CHECK (user_id = app_user_id());

-- ---------------------------------------------------------------- pre-auth functions

-- Login needs a lookup by email before any user context exists; registration must insert a user.
-- These are the only ways glassbox_app reaches users outside its own context.
CREATE FUNCTION auth_find_user(p_email text)
    RETURNS TABLE (id uuid, email text, password_hash text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public, pg_temp
AS $$ SELECT u.id, u.email, u.password_hash FROM users u WHERE lower(u.email) = lower(p_email) $$;

CREATE FUNCTION auth_create_user(p_email text, p_password_hash text) RETURNS uuid
    LANGUAGE sql SECURITY DEFINER SET search_path = public, pg_temp
AS $$ INSERT INTO users (email, password_hash) VALUES (p_email, p_password_hash) RETURNING id $$;

REVOKE ALL ON FUNCTION auth_find_user(text), auth_create_user(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION auth_find_user(text), auth_create_user(text, text) TO glassbox_app;

-- ---------------------------------------------------------------- grants

GRANT USAGE ON SCHEMA public TO glassbox_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON workspaces, memberships, api_keys, provider_keys, refresh_tokens
    TO glassbox_app;
GRANT SELECT, UPDATE ON users TO glassbox_app;        -- no direct INSERT/DELETE: use auth_create_user
GRANT SELECT, INSERT ON audit_log TO glassbox_app;    -- append-only
