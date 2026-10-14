# Glassbox

Multi-tenant RAG evaluation and auto-tuning platform: upload documents, generate a test set, search configurations, compare them with confidence intervals, and diagnose every failure. The full plan is in [docs/SPEC.md](docs/SPEC.md).

## Status

| Phase | State |
| --- | --- |
| P0 Foundation: tenancy with Postgres row-level security, JWT auth, roles, CI | done |
| P1 Ingest, retrieve, chat: queue, parsers, chunkers, embeddings, hybrid search, rerank, streaming cited answers | done |
| P2 X-Ray trace viewer, test sets, review queue | done |
| P3 Eval Lab: experiments, metrics, confidence intervals and significance tests, claim verification, judge calibration | done |

What works today: upload almost any document (PDF, Word, Excel, PowerPoint, OpenDocument, RTF, EPUB, Markdown, HTML, CSV, JSON, XML, plain text and source code; images, audio and video need OCR or transcription and are refused with a clear reason); asynchronous ingestion with live status; hybrid (vector + keyword) search fused inside Postgres; optional cross-encoder reranking; streamed answers with clickable citations; an answer that says "not in the documents" instead of guessing; tenant isolation enforced by the database; X-Ray for every answer; versioned test sets generated from your own documents, with a keyboard-driven review queue; experiments that compare pipeline configurations with confidence intervals and tests of significance; claim-by-claim checking of answers; and a calibration page that measures how far the answer judge can be trusted.

## The interface

A ChatGPT-style chat with a sidebar of past conversations, live progress while an answer is prepared, clickable citations that open the exact passage in a slide-over, a library of documents with drag-anywhere upload, X-Ray for every answer, test sets with a review screen, an Eval Lab (experiments and judge calibration), and a Ctrl K command palette. It comes in two flat themes, pure black and pure white, with a single indigo and teal accent for text, buttons and highlights, and a 3-second splash screen on load.

### Test sets

Generate a test set from the documents in your library (Test sets, then Generate), and the model writes questions with expected answers from sampled passages. Each question carries its gold evidence as an exact character range of the document, and quality flags (copies the text, not self-contained, hard to answer, duplicate). You then review the questions, one per screen, with the evidence highlighted in the document:

| Key | Action |
| --- | --- |
| A / R | accept / reject, then jump to the next pending question |
| E | edit the question, the answer or the evidence (select text in the document to change it) |
| J / K | next / previous |
| P | put back to pending |

"Accept all unflagged" takes the clean questions in one go. A draft is finalized to freeze it; a final version never changes, and "New version" makes an editable copy whose differences can be compared question by question. Design notes: [ADR 0003](docs/adr/0003-draft-and-final-test-set-versions.md).

### Eval Lab

**Experiments** measure pipeline configurations on a finalized test set. Pick the configurations (vector, keyword, hybrid, hybrid with a reranker, or edit the fields), see the estimate, and run. The first configuration is the baseline; every other one is compared with it on the same questions.

- *Retrieval only* (seconds, no model tokens): does the gold evidence reach the prompt? Recall@5 and @10, MRR, nDCG@10, context recall, latency.
- *Full answers*: also writes each answer, splits it into statements and checks each against the retrieved text (an NLI model first, the LLM judge for the unclear cases), has the judge grade correctness and relevance, and scores abstention. Every answer gets a trace that opens in X-Ray. Can run on a fixed random sample of the questions, because answering is slow on a small local model.
- Every number comes with a 95% bootstrap interval (and an interval by source document, since questions from one document move together). A difference is called real only when the interval excludes zero **and** the corrected p-value is below 0.05, in the sentence form "B beats A by +4.1 points, 95% CI [+0.8, +7.5], p=0.02". The page also says how small a difference the test set could detect, shows the changes as a forest plot, breaks results down by question type (cells with fewer than 10 questions are hatched), and lists the questions that changed sides between two runs with a link to each one's X-Ray trace. Method and its checks: [ADR 0005](docs/adr/0005-statistics-and-judge-calibration.md).

**Calibration** is where a person labels a sample of what the judge graded, without seeing its verdict (the sample is balanced across the judge's verdicts). The page shows agreement, Cohen's kappa, the confusion matrix and precision and recall per verdict, per judge model and rubric version. A judge below kappa 0.6, or with fewer than 30 labels, leaves an "uncalibrated" badge next to every number it produced, and the page warns that the model writing the answers is also the one grading them.

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
cd apps/api && ./mvnw verify      # 286 tests: isolation, queue concurrency, chunker properties, retrieval SQL, chat, traces, test sets, statistics against SciPy fixtures, experiments, calibration
cd apps/web && npm ci && npm test # 84 tests: SSE parser, highlighting, review shortcuts, selection offsets, forest plot and heatmap layout, calibration queue; also npm run lint and npm run build
cd apps/ml-sidecar && pip install -r requirements-dev.txt && python -m pytest
python benchmarks/stats_fixtures.py   # regenerates the SciPy reference values the Java statistics are checked against
```

Tests never call a live model or API: they use WireMock fixtures and deterministic fakes.

## Findings so far

Things measured while building, each with a test that pins it:

- **Filtered vector search can silently return too few rows.** With another tenant's closer vectors in the index, a plain HNSW scan returned fewer than the 20 requested rows; with the filters on the indexed table plus pgvector's iterative scan it returns all 20. See [ADR 0001](docs/adr/0001-rls-and-full-text-search.md).
- **Full-text search cannot use its GIN index under row-level security** because the match operator is not leakproof. The search stays bounded to one tenant's chunks instead. Same ADR.
- **The spec's keyword query requires every word to match,** so natural questions found nothing; matching any word is the default. [ADR 0002](docs/adr/0002-keyword-matching-any-vs-all.md).
- **A queue lock that expires needs a heartbeat.** Test set generation runs for minutes, but the queue hands a job to another worker when its lock is five minutes old. The first acceptance run produced 67 questions for a target of 50 because two runs of one job wrote side by side. Fixed twice: a long job now reports in after every model call, and each question claims its position with a compare-and-set, so a stale run stops instead of writing (`TestSetApiTest.aRunThatFindsAnotherRunAheadOfItWritesNothing`, `JobQueueTest.aHeartbeatKeepsALongRunningJobFromBeingReaped`).
- **The "best sentence" is not always evidence.** For paired passages the first version of the evidence locator always picked a sentence from the second passage, even when the answer was not in it (one gold span was the heading "Signing in"). Evidence now has to hold at least a quarter of the answer's words, otherwise the passage is dropped; an answer found in no passage skips the sample.
- **A small NLI model fails on long premises.** The claim checker first handed the NLI model the whole chunk. A claim that appears word for word in a three-sentence chunk scored 0.03 entailment; the same claim against its own sentence scored 0.98, and against that sentence plus one neighbour 0.96. The checker now scores every sentence and every neighbouring pair on its own and keeps the best (verifier `windows-v2`; `ClaimsUnitTest.aClaimThatIsInTheChunkWordForWordIsFoundEvenWhenTheModelFailsOnLongPremises`). Faithfulness agreement with a human went from 72.5% to 77.5% (kappa 0.07 to 0.24).
- **Correcting across a comparison's metrics hid a real gain.** Holm applied over the seven retrieval metrics of one comparison turned a clear 20-point improvement (exact p = 0.008) into p = 0.055, "not significant", though the metrics all describe the same retrieval. The correction is now across configurations, one metric at a time, as SPEC 8 asks.
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

### P3 acceptance run: Eval Lab on the P2 test set

`python benchmarks/eval_demo.py --full 20` on the 43 accepted questions of the P2 test set (`llama3.2:3b`, a laptop CPU). What the pages read.

**Retrieval, four configurations (172 searches, 38 s, no model tokens).** Evidence in the prompt: Vector 97.7% [93.0, 100.0], Keyword 97.7% [93.0, 100.0], Hybrid 100% [100, 100], Hybrid + rerank 100% [100, 100]. MRR: 93.4%, 89.2%, 95.3%, 98.8%. Median latency: 16 ms, 2 ms, 16 ms, 740 ms. Verdict for every configuration against Vector: *"No significant difference (CI [+0.0, +7.0], p=1.00)"*, and the page adds that this test set can detect about 6.5 to 9.3 points. On a corpus this small and easy everything is near the ceiling, so the honest answer is that the test set cannot separate them, not that they are equal.

**A comparison that does find a difference.** How many chunks reach the prompt, Top 1 / Top 3 / Top 5: evidence in the prompt 83.7%, 97.7%, 97.7%. Top 1 against Top 3 on its own: *"B beats A by +14.0 points, 95% CI [+4.7, +25.6], p=0.03"*, driven by 6 questions whose evidence was missed with Top 1 and found with Top 3. The same data as part of a three-configuration experiment gets *"No significant difference (CI [+4.7, +25.6], p=0.06)"*: the p-value is corrected for comparing two configurations with one baseline, and a result that survives one comparison but not the correction is not reported as real.

**Full answers, 20 sampled questions, Vector against Hybrid (10.7 min, about 16 s per answer including checks and judging).** Correctness 85.0% [70.0, 97.5] and 82.5% [65.0, 97.5]; faithfulness 83.3% and 86.1%; relevance 87.5% and 90.0%; citation precision 92.3% and 91.7%; no false abstentions. Verdict: no significant difference, the sample could detect about 21.5 points. Every judged number carries the uncalibrated badge.

**Calibration, 40 labels per metric.** The labels were assigned by Claude acting as the labeller, reading each item as a person would; a person should redo them for a kappa that reflects their own standard. The mechanism and the disagreement are real:

| Judge | Agreement | Kappa | Status | What went wrong |
| --- | --- | --- | --- | --- |
| Correctness | 87.5% | 0.59 | uncalibrated | just under the 0.6 bar; too harsh: 3 partly right answers called wrong, 2 right ones called partial |
| Faithfulness (claims) | 77.5% | 0.24 | uncalibrated | fooled by word overlap ("payroll data are restricted" scored 0.99 where the passage says confidential); strict on near-paraphrases |
| Relevance | 75.0% | 0.00 | uncalibrated | called 10 of 40 fully relevant answers "no" or "partly"; the person said "yes" to all 40, so kappa has nothing to measure |

## Limitations (so far)

- Answers from a small local model sometimes omit a citation; claim-level verification arrives in P3.
- Answer text is streamed as the model writes it and replaced by a sanitized version (links and images removed) when it finishes; the UI renders plain text only. Full prompt-injection testing is P6.
- Chunk and token sizes use a simple word-and-punctuation tokenizer, so they run below a real tokenizer's counts.
- No OCR: scanned PDFs are rejected with a clear message.
- Eval Lab: experiments vary retrieval settings on the existing index; different chunkings and embedding models arrive with AutoTune in P4. The judge is the model that writes the answers. Claim checking uses a small NLI model that over-trusts word overlap and under-trusts paraphrase. The sample labels in the acceptance run were not given by a person. Experiment answer traces are kept for the trace retention period (30 days), after which their X-Ray links stop opening.
- Test sets: unanswerable questions can be added by hand but are not generated yet (P4); the answerability judge is lexical, not a model judge (P3); quality flags are not recomputed when a reviewer edits a question; generation uses the chat model (a separate generator model is not configurable yet).
