# ADR 0008: Query transformers, the temperature-0 cache, and how a run is priced and capped

Status: accepted (2026-10-15).

## Query transformers

`multi_query` (three paraphrases), `hyde` (a hypothetical answer is embedded in place of the question) and `decompose` (sub-questions) sit behind one interface and are a field of
`PipelineConfig`. Every query a transformer produces is searched; the result lists are fused with Reciprocal Rank Fusion (`QueryFusion`: a chunk several queries agree on beats one
found by a single query). HyDE keeps the question for the keyword side, because a hypothetical answer may name the wrong things.

Rules that keep a transformer from making things worse:

- **A reply that cannot be used falls back to the plain question.** An error from the model, an empty reply, a one-line "I don't know" for HyDE: the search is the same as with no transformer
  (tested for all three).
- **No transformer is one value.** `NONE` is stored as null and left out of the JSON, so the config hash of every experiment made before transformers existed is unchanged.
- **Each has a cost entry** (model calls and tokens per question), a `query_transform` span that X-Ray shows (the queries produced, calls, tokens), and an AutoTune entry.
- **AutoTune only varies the transformer when asked.** A transformer calls the model for every question, which a retrieval-only search otherwise never does, so it is an option, its calls
  count against the spend cap, and the estimate says how many there are.
- **`rewrite` is not implemented.** It needs conversation history, and chat sends none. It is the first thing to add when chat has memory.
- A one-click recommendation exists: partial retrievals suggest `decompose`; once decompose is on, they suggest more chunks in the prompt.

## The temperature-0 cache

`CachedLlm` stores the reply to a call at temperature 0 under a hash of the model, parameters and messages, per workspace (row-level security, so one tenant's prompts are never served to
another). A call with a temperature above 0 is never cached. The transformers use it: repeating an experiment costs no transformer calls (tested: the second run makes none), and the
measured hit rate feeds the estimator. Judge and claim-check calls still go straight to the model; routing them through the cache is the next saving.

## Pricing, budgets and the abort rule

- **Estimate** = questions x configurations x stages x tokens x price, discounted by the cache hit rate for the deterministic calls (judging, rewriting) and not for answers. The breakdown
  is embeddings, answers, judging, query rewriting (question synthesis is priced when a test set is written). `CostEstimator` is pure.
- **A local model costs $0**, so every breakdown also carries tokens and calls, which is what a budget on a local setup is spent in.
- **Prices** are a per-workspace table by model, with the configured defaults for a model without a row.
- **Limits:** a monthly budget, a per-run cap and a "confirm above" threshold. A run whose estimate passes the cap or would take the month past the budget is refused with the numbers,
  before anything is created; above the threshold it needs an explicit confirmation.
- **Abort at 120%.** A running experiment is checked before each question; when its spend (dollars or model calls) passes 120% of its cap it stops, keeps what it measured and is marked
  `ABORTED`. Checking before each question means a run overshoots by at most one question, which is why the rule is 120% and not 100%. AutoTune stops at its own caps (minutes, searches,
  model calls) instead and keeps the best so far.
- **Estimate against actual** is recorded for every finished or aborted run (`spend_log`); the budget page shows the estimator's own error. The estimate assumes full-size prompts, so
  against the small fake models of the tests it over-estimates; against a real model it is to be measured.

## Not done

Dollar caps for AutoTune (it has call, search and minute caps), a cache for judge calls, and a monthly budget on model calls (only dollars are enforced monthly).
