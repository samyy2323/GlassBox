#!/bin/bash
# Runs once on first start of the postgres container (as the superuser).
# Creates the two application roles and installs extensions that need a superuser.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v owner_pw="$GLASSBOX_OWNER_PASSWORD" -v app_pw="$GLASSBOX_APP_PASSWORD" <<'SQL'
-- glassbox_owner runs migrations and owns every table.
CREATE ROLE glassbox_owner LOGIN PASSWORD :'owner_pw' NOSUPERUSER NOCREATEROLE NOCREATEDB;
-- glassbox_app is the runtime role: owns nothing, cannot bypass RLS.
CREATE ROLE glassbox_app   LOGIN PASSWORD :'app_pw'   NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS;

CREATE EXTENSION IF NOT EXISTS vector;

SELECT current_database() AS db \gset
ALTER DATABASE :"db" OWNER TO glassbox_owner;
ALTER SCHEMA public OWNER TO glassbox_owner;
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO glassbox_owner, glassbox_app;
SQL
