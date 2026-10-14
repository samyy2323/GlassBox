# Glassbox project rules
- Read docs/SPEC.md before any task. Sections are referenced by number.
- Stack: Java 21, Spring Boot 3, PostgreSQL 16 + pgvector, React + TypeScript + Vite.
- Retrieval and evaluation SQL is hand-written (JdbcTemplate or jOOQ), not JPA.
- Every tenant table has workspace_id and RLS. Never connect as the table owner at runtime.
- Every feature ships with tests. Integration tests use Testcontainers with the pgvector image.
- LLM and embedding calls go through provider interfaces. Tests use WireMock fixtures, never live APIs.
- No secrets in code or logs. Configuration comes from environment variables.
- Work phase by phase (SPEC section 16). Do not start a phase until the previous acceptance check passes.
- After each task: run build and tests, then summarize what changed and what remains.

## Layout
apps/web (React), apps/api (Spring Boot, profiles api/worker), apps/ml-sidecar (FastAPI), cli, db/migrations (Flyway), deploy, benchmarks, docs.
