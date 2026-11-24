#!/bin/bash
# Starts a throwaway database, loads the recorded demo into it, and hands over to supervisord. The disk is erased on every restart, so the demo is restored each time:
# nothing a visitor creates survives, and the demo itself cannot be damaged.
set -euo pipefail

export PGDATA=/home/user/pgdata
export GLASSBOX_STORAGE_DIR=/home/user/files
mkdir -p "$GLASSBOX_STORAGE_DIR"

# Secrets are made here, per start, and never written to the image.
export GLASSBOX_OWNER_PASSWORD="$(openssl rand -hex 16)"
export GLASSBOX_APP_PASSWORD="$(openssl rand -hex 16)"
export GLASSBOX_JWT_SECRET="${GLASSBOX_JWT_SECRET:-$(openssl rand -hex 32)}"
export POSTGRES_USER=postgres POSTGRES_DB=glassbox PGHOST=/tmp

initdb -D "$PGDATA" -U postgres --auth=trust --encoding=UTF8 >/dev/null
pg_ctl -D "$PGDATA" -o "-c listen_addresses=127.0.0.1 -c unix_socket_directories=/tmp" -w start >/dev/null
createdb -U postgres glassbox
bash /app/01-roles.sh >/dev/null

if [ -f /app/seed/demo.dump ]; then
  echo "[space] restoring the recorded demo"
  pg_restore -U postgres -d glassbox --no-owner --role=glassbox_owner /app/seed/demo.dump || echo "[space] demo restore reported problems"
  [ -f /app/seed/files.tar.gz ] && tar -xzf /app/seed/files.tar.gz -C "$GLASSBOX_STORAGE_DIR"
fi
pg_ctl -D "$PGDATA" -m fast -w stop >/dev/null

export GLASSBOX_DB_URL=jdbc:postgresql://127.0.0.1:5432/glassbox
export GLASSBOX_SIDECAR_URL=http://127.0.0.1:8000
export GLASSBOX_CORS_ORIGINS=http://localhost:7860
export GLASSBOX_COOKIE_SECURE=true
export GLASSBOX_DEMO_ENABLED="${GLASSBOX_DEMO_ENABLED:-true}"
export GLASSBOX_LLM_BASE_URL="${GLASSBOX_LLM_BASE_URL:-https://api.groq.com/openai}"
export GLASSBOX_LLM_MODEL="${GLASSBOX_LLM_MODEL:-llama-3.1-8b-instant}"
export LOGGING_STRUCTURED_FORMAT_CONSOLE=logstash

exec supervisord -c /etc/supervisord.conf
