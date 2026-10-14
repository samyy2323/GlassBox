# Glassbox

Multi-tenant RAG evaluation and auto-tuning platform: upload documents, generate a test set, search configurations, compare them with confidence intervals, and diagnose every failure. The full plan is in [docs/SPEC.md](docs/SPEC.md).

## Status

| Phase | State |
| --- | --- |
| P0 Foundation: tenancy with Postgres row-level security, JWT auth, roles, CI | done |
| P1 Ingest, retrieve, chat: queue, parsers, chunkers, embeddings, hybrid search, rerank, streaming cited answers | done |
| P2 X-Ray trace viewer, test sets, review queue | X-Ray done; test sets and review queue next |

What works today: upload almost any document (PDF, Word, Excel, PowerPoint, OpenDocument, RTF, EPUB, Markdown, HTML, CSV, JSON, XML, plain text and source code; images, audio and video need OCR or transcription and are refused with a clear reason); asynchronous ingestion with live status; hybrid (vector + keyword) search fused inside Postgres; optional cross-encoder reranking; streamed answers with clickable citations; an answer that says "not in the documents" instead of guessing; tenant isolation enforced by the database.

## The interface

A ChatGPT-style chat with a sidebar of past conversations, live progress while an answer is prepared, clickable citations that open the exact passage in a slide-over, a library of documents with drag-anywhere upload, X-Ray for every answer, and a Ctrl K command palette. It comes in two flat themes, pure black and pure white, with a single indigo and teal accent for text, buttons and highlights, and a 3-second splash screen on load.

## Run it

You need Docker. For answers you also need a language model: by default the app talks to [Ollama](https://ollama.com) on your machine.

```bash
ollama pull llama3.2:3b                  # a free 3B model; any OpenAI-compatible endpoint works (see .env.example)
cp .env.example .env                     # then put random strings in the secrets
docker compose -f deploy/docker-compose.yml --env-file .env up --build
```

Open http://localhost:8080, create an account, upload a few documents on the Documents page, then ask about them on the Chat page. API health: http://localhost:8081/actuator/health.

The first build downloads Maven and Python dependencies and the embedding models, which can take a long time on a slow connection.

## Test it

Needs JDK 21 and Docker (Testcontainers starts a real pgvector Postgres):

```bash
cd apps/api && ./mvnw verify      # 138 tests: isolation, queue concurrency, chunker properties, retrieval SQL, chat
cd apps/web && npm ci && npm test # SSE parser tests; also npm run lint and npm run build
cd apps/ml-sidecar && pip install -r requirements-dev.txt && python -m pytest
```

Tests never call a live model or API: they use WireMock fixtures and deterministic fakes.

## Findings so far

Things measured while building, each with a test that pins it:

- **Filtered vector search can silently return too few rows.** With another tenant's closer vectors in the index, a plain HNSW scan returned fewer than the 20 requested rows; with the filters on the indexed table plus pgvector's iterative scan it returns all 20. See [ADR 0001](docs/adr/0001-rls-and-full-text-search.md).
- **Full-text search cannot use its GIN index under row-level security** because the match operator is not leakproof. The search stays bounded to one tenant's chunks instead. Same ADR.
- **The spec's keyword query requires every word to match,** so natural questions found nothing; matching any word is the default. [ADR 0002](docs/adr/0002-keyword-matching-any-vs-all.md).
- **Prompt wording decides whether a 3B model works at all.** The first prompt made `llama3.2:3b` refuse every answerable question (0 of 3); the current one answers 3 of 3 and still abstains 3 of 3 on unanswerable ones. Reproduce with `python benchmarks/prompt_check.py`.

## Limitations (so far)

- Answers from a small local model sometimes omit a citation; claim-level verification arrives in P3.
- Answer text is streamed as the model writes it and replaced by a sanitized version (links and images removed) when it finishes; the UI renders plain text only. Full prompt-injection testing is P6.
- Chunk and token sizes use a simple word-and-punctuation tokenizer, so they run below a real tokenizer's counts.
- No OCR: scanned PDFs are rejected with a clear message.
