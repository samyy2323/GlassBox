# ADR 0007: The failure taxonomy as a pure function, and what a parent_child chunk means for the metrics

Status: accepted (2026-10-15).

## Failure classification

A question fails when its gold evidence did not reach the prompt, or its answer is not fully correct. `FailureClassifier.classify` labels it with the
first pipeline stage that failed. It is a pure function of facts stored with the result, deterministic on purpose, so every row of the taxonomy has a
test and a label can be explained by reading the stored trace.

For each gold span the runner records: the largest share of the span that any single chunk of the chunking covers (looked up only for spans that
did not reach the prompt), the span's rank in the fused list and after reranking, whether it was in the top n, whether it reached the prompt and, if
not, why (`BUDGET`, `DUPLICATE`, `OVERLAP`). Stages, in order:

| Stage | Rule |
| --- | --- |
| CHUNKING_MISS | the prompt lacks a span and no single chunk covers at least 80% of it |
| RETRIEVAL_MISS | a chunk covers it, but it is not among the fused candidates |
| RANKING_MISS | among the candidates, but below the final top n |
| CONTEXT_MISS | in the top n, but dropped by the token budget or de-duplication |
| PARTIAL_RETRIEVAL | a question with several spans that got some into the prompt and not all |
| GENERATION_MISS | all evidence in the prompt, answer wrong; sub-label FALSE_ABSTENTION, UNFAITHFUL, INCOMPLETE or WRONG_REASONING, in that order |
| HALLUCINATED_ANSWER | an unanswerable question that was answered |
| BAD_TEST_QUESTION | evidence in the prompt, answer wrong, and the question carries an AMBIGUOUS or LOW_ANSWERABILITY flag |

Decisions where the SPEC table leaves room:

- **CHUNKING_MISS outranks PARTIAL_RETRIEVAL.** If a missing span cannot be held by any chunk, no later stage can repair it. Otherwise a partial
  retrieval keeps the stage at which its missing span was lost (`lostAt`), which is what the recommendation needs.
- **A retrieval failure is never blamed on the question or the model.** BAD_TEST_QUESTION is only considered once the evidence reached the prompt.
- **A refusal with the evidence in the prompt is a false abstention**, even when the question is flagged.
- **A correct answer that is not fully faithful is not a failure.** Faithfulness is reported, not a pass/fail.

The report adds rule-based advice ("38% of failures are ranking misses, so add a reranker"). A stage must explain at least 10% of the failures to get
advice. Advice that can be tried carries the one setting to change, in the shape of an experiment configuration; the button opens a new experiment of the
run as it was against the run with exactly that change.

## parent_child chunks

The `parent_child` chunker stores large PARENT chunks (never embedded, never searched) and small CHILD chunks (embedded and searched). When a child is
retrieved, the prompt gets its parent. Consequences, all deliberate:

- Retrieval works on children, and the pipeline replaces the reranked list with the parents of its children (a parent that several children point to
  appears once, at its best child's place) before the top n is taken. So **top n counts parents**, and every metric (recall@k, MRR, nDCG, context
  recall) is computed on the units that reach the prompt. Candidates in the fused list stay children, and X-Ray shows both.
- Gold evidence is matched against the chunk that reaches the prompt. A small child rarely covers half of a long gold span, a parent can, so measuring
  children would punish this chunker for exactly what it is for.
- The 80% coverage rule of CHUNKING_MISS looks at all chunks of the chunking, parents included.
- `ContextBuilder` is unchanged; a parent is just a chunk with more text, so the token budget naturally penalises large parents, which the search then sees as
  a higher prompt-token cost.

## Indexing several chunkings

Uploads still create only the default chunking and embedding model. `IndexService` builds any other pair on demand from the stored files, for every
current document, before an experiment or AutoTune searches it. It is idempotent (a document that already has the chunks and all vectors is skipped, identical
text reuses its vector) and does not touch a document's status. Sections are written once per version and reused by every chunking.
