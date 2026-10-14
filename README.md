# Glassbox

Multi-tenant RAG evaluation and auto-tuning platform: upload documents, generate a test set, search configurations, compare with confidence intervals, diagnose failures. See [docs/SPEC.md](docs/SPEC.md).

**Status:** Phase P0 (foundation) complete: tenancy schema with Postgres Row-Level Security, JWT auth, workspaces and roles, API keys, React shell, Docker Compose stack, CI.

## Run it

```bash
cp .env.example .env        # then fill in the secrets (any random strings; JWT secret >= 32 chars)
docker compose -f deploy/docker-compose.yml --env-file .env up --build
```

Open http://localhost:8080 (web), API health at http://localhost:8081/actuator/health.

## Test it

Needs JDK 21 and Docker (Testcontainers starts a pgvector Postgres):

```bash
cd apps/api && ./mvnw verify
```
