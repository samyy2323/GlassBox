# Glassbox

Multi-tenant RAG evaluation and auto-tuning platform: upload documents, generate a test set, search configurations, compare them with confidence intervals, and diagnose every failure. The full plan is in [docs/SPEC.md](docs/SPEC.md).

## Status

| Phase | State |
| --- | --- |
| P0 Foundation: tenancy with Postgres row-level security, JWT auth, roles, CI | done |
| P1 Ingest, retrieve, chat: queue, parsers, chunkers, embeddings, hybrid search, rerank, streaming cited answers | done |
| P2 X-Ray trace viewer, test sets, review queue | done |

What works today: upload almost any document (PDF, Word, Excel, PowerPoint, OpenDocument, RTF, EPUB, Markdown, HTML, CSV, JSON, XML, plain text and source code; images, audio and video need OCR or transcription and are refused with a clear reason); asynchronous ingestion with live status; hybrid (vector + keyword) search fused inside Postgres; optional cross-encoder reranking; streamed answers with clickable citations; an answer that says "not in the documents" instead of guessing; tenant isolation enforced by the database; X-Ray for every answer; versioned test sets generated from your own documents, with a keyboard-driven review queue.

## The interface

A ChatGPT-style chat with a sidebar of past conversations, live progress while an answer is prepared, clickable citations that open the exact passage in a slide-over, a library of documents with drag-anywhere upload, X-Ray for every answer, test sets with a review screen, and a Ctrl K command palette. It comes in two flat themes, pure black and pure white, with a single indigo and teal accent for text, buttons and highlights, and a 3-second splash screen on load.

### Test sets

Generate a test set from the documents in your library (Test sets, then Generate), and the model writes questions with expected answers from sampled passages. Each question carries its gold evidence as an exact character range of the document, and quality flags (copies the text, not self-contained, hard to answer, duplicate). You then review the questions, one per screen, with the evidence highlighted in the document:

| Key | Action |
| --- | --- |
| A / R | accept / reject, then jump to the next pending question |
| E | edit the question, the answer or the evidence (select text in the document to change it) |
| J / K | next / previous |
| P | put back to pending |

"Accept all unflagged" takes the clean questions in one go. A draft is finalized to freeze it; a final version never changes, and "New version" makes an editable copy whose differences can be compared question by question. Design notes: [ADR 0003](docs/adr/0003-draft-and-final-test-set-versions.md).

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
cd apps/api && ./mvnw verify      # 207 tests: isolation, queue concurrency, chunker properties, retrieval SQL, chat, traces, test sets
cd apps/web && npm ci && npm test # 64 tests: SSE parser, highlighting, review shortcuts, selection offsets; also npm run lint and npm run build
cd apps/ml-sidecar && pip install -r requirements-dev.txt && python -m pytest
```

Tests never call a live model or API: they use WireMock fixtures and deterministic fakes.

## Findings so far

Things measured while building, each with a test that pins it:

- **Filtered vector search can silently return too few rows.** With another tenant's closer vectors in the index, a plain HNSW scan returned fewer than the 20 requested rows; with the filters on the indexed table plus pgvector's iterative scan it returns all 20. See [ADR 0001](docs/adr/0001-rls-and-full-text-search.md).
- **Full-text search cannot use its GIN index under row-level security** because the match operator is not leakproof. The search stays bounded to one tenant's chunks instead. Same ADR.
- **The spec's keyword query requires every word to match,** so natural questions found nothing; matching any word is the default. [ADR 0002](docs/adr/0002-keyword-matching-any-vs-all.md).
- **A queue lock that expires needs a heartbeat.** Test set generation runs for minutes, but the queue hands a job to another worker when its lock is five minutes old. The first acceptance run produced 67 questions for a target of 50 because two runs of one job wrote side by side. Fixed twice: a long job now reports in after every model call, and each question claims its position with a compare-and-set, so a stale run stops instead of writing (`TestSetApiTest.aRunThatFindsAnotherRunAheadOfItWritesNothing`, `JobQueueTest.aHeartbeatKeepsALongRunningJobFromBeingReaped`).
- **The "best sentence" is not always evidence.** For paired passages the first version of the evidence locator always picked a sentence from the second passage, even when the answer was not in it (one gold span was the heading "Signing in"). Evidence now has to hold at least a quarter of the answer's words, otherwise the passage is dropped; an answer found in no passage skips the sample.
- **Prompt wording decides whether a 3B model works at all.** The first prompt made `llama3.2:3b` refuse every answerable question (0 of 3); the current one answers 3 of 3 and still abstains 3 of 3 on unanswerable ones. Reproduce with `python benchmarks/prompt_check.py`.

### P2 acceptance run: 50 questions from 5 documents

`python benchmarks/testset_demo.py` (five policy documents in `benchmarks/corpus`, twelve sections each, `llama3.2:3b` on a laptop CPU, quality checks on). The generated set was then reviewed in the UI with the keyboard shortcuts, finalized, and copied to a version 2 to try the diff.

| | |
| --- | --- |
| Questions generated | 50 in 13.2 minutes (about 16 s per question, 3 model calls each) |
| Types written | 31 factual, 12 numeric, 5 comparison, 2 multi-hop |
| Flagged by the quality checks | 31 of 50 (23 not self-contained, 8 copy the text, 9 hard to answer) |
| After review | 38 accepted as written, 4 edited, 8 rejected: **84% kept** |

- **The flags are noisy, as expected from a 3B judge.** Of the 31 flagged questions, 22 were fine as they were, 2 needed an edit and 7 were rejected. Of the 19 unflagged ones, 3 still needed attention (1 rejected, 2 edited). So the flags find about three quarters of the problems (9 of 12) but only 29% of what they flag is a real problem. "Accept all unflagged" is a time saver, not a substitute for reading.
- **A small model rarely writes a question that truly needs two passages.** 30% of the samples asked for a multi-hop or comparison question, but in most of them the model used one passage only. The locator now says so: a passage that holds none of the answer is dropped from the evidence and the question is labelled as a one-passage question. Only 7 of the 50 kept questions still use two passages; the reviewer rejected 3 of those and relabelled 2 more as one-passage. Real multi-hop questions need a stronger model.

## Limitations (so far)

- Answers from a small local model sometimes omit a citation; claim-level verification arrives in P3.
- Answer text is streamed as the model writes it and replaced by a sanitized version (links and images removed) when it finishes; the UI renders plain text only. Full prompt-injection testing is P6.
- Chunk and token sizes use a simple word-and-punctuation tokenizer, so they run below a real tokenizer's counts.
- No OCR: scanned PDFs are rejected with a clear message.
- Test sets: unanswerable questions can be added by hand but are not generated yet (P4); the answerability judge is lexical, not a model judge (P3); quality flags are not recomputed when a reviewer edits a question; generation uses the chat model (a separate generator model is not configurable yet).
