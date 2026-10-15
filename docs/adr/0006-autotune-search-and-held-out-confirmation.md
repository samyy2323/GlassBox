# ADR 0006: How AutoTune searches, and why its headline numbers come only from questions it never saw

Status: accepted (2026-10-15).

Choosing the best of many configurations on a test set overstates how good the winner is: some of what looked like skill was luck on those
particular questions (the winner's curse). With a few dozen to a few hundred questions the overstatement is several points, as large as the
differences the search is looking for. AutoTune is therefore built so that no number it reports was also used to make a choice.

## The split

The test set is divided once, at the start, into a **tuning** part (about 70%) and a **held-out** part (about 30%) by `Splits`. It is stratified
by question type (each type gives 30% of its questions, and at least one when it has two or more), so the held-out part is a small copy of the whole
set and not a slice that happens to hold only easy questions. Which question lands where depends only on the seed and the question's id: the same
test set and seed always give the same split, and reordering the questions changes nothing.

Every choice (which chunking, which retrieval settings, which finalists, which configuration is the winner) is made from tuning scores. The held-out
questions are first read in stage 4, for the finalists and the baseline only. A test (`noChoiceIsEverMadeOnHeldOutQuestions`) checks this against the
database: the per-question scores stored by stages 1 and 2 contain no held-out question id, and the held-out runs contain nothing else.

## The search

A full grid (4 chunkers x 3 sizes x 3 overlaps x 3 embedding models x retrievers x candidate counts x rerankers x top n x budgets) has thousands
of points, so it is searched in stages, as SPEC 9 asks:

1. **Chunking and embedding model**, other settings at the baseline's. A deterministic sample of the grid (default 18, always including the
   baseline) is narrowed by **successive halving**: every candidate is scored on a quarter of the tuning questions, the top third goes on to twice as
   many, and so on until the survivors have seen all of them. Candidates that are clearly behind are dropped after a few cheap questions.
2. **Retrieval settings** (vector or hybrid with three weightings, candidates, reranker, top n, context budget) around the stage 1 winner, the same way.
3. **Full answers** for the finalists and the baseline on the tuning questions (optional): generation, claim checking, judging.
4. **Held-out confirmation** of the finalists and the baseline.

Stages 1 and 2 need no language model: their score is context recall (does the gold evidence reach the prompt), measured by the same retrieval
pipeline that answers questions. This is what makes it affordable to try dozens of configurations on a laptop.

The score AutoTune optimises is a blend, 50% context recall, 30% correctness, 20% faithfulness, with every component also reported. When a component
does not exist for a question (a retrieval-only run has no answer; an answer with no claims has no faithfulness) the weights of the others are scaled
up, so a missing component never counts as zero. With the full stage off, the score is context recall alone and the report says so.

Halving prunes by quality alone. The **objective** decides among the survivors: best quality; cheapest above a quality floor (cost is the prompt
tokens a question needs, what a paid model would bill for); or best quality within a latency cap. (SPEC names the third "fastest within a latency
cap"; picking the fastest regardless of quality would choose a useless configuration, so it ranks by quality among those under the cap.)

## What is reported

For the finalists and the baseline, measured on the held-out questions: the quality with a 95% bootstrap interval, the paired difference from the baseline
with its interval, a sign-flip permutation p-value, Holm-corrected across the finalists, and the same verdict sentence the Eval Lab uses. The smallest
difference the held-out set could detect is shown, because with a small held-out set "no significant difference" means "cannot tell". The tuning score
of the winner is shown beside its held-out score, so the shrinkage is visible.

The **Pareto frontier** is computed over quality, prompt tokens and latency from held-out numbers. Three picks: *best quality*; *best value* (most quality
per prompt token among frontier points within 90% of the best quality, so a cheap useless configuration cannot win); and *fastest within 3 points of
the best*. A table of the average quality for each value of each setting shows what mattered. It is computed from the questions every candidate in a stage
was scored on (the first round), so its averages are comparable.

## Spend cap

A local model costs no money, so the cap is in what it does use: minutes, searches and model calls (and dollars where a price is configured). The first
limit reached stops stages 1 to 3 and keeps the best result so far. The held-out check still runs, retrieval-only, because it is cheap and it is the only
honest number. The report says the search was cut short.

## Resumability

Per-question scores of stages 1 and 2 are stored with each candidate; the runs of stages 3 and 4 are ordinary experiment runs whose results are unique per
(run, question); halving is deterministic. A restarted job asks for scores it already has and repeats nothing (tested by cutting the embedding service
mid-search).

## Promote

Promoting writes the winner to `workspace_settings.default_pipeline`. Chat and search start from it, and new uploads are indexed with its chunking and
embedding model before they count as ready, so the promoted configuration never searches a half-indexed library. Only an owner can promote.

## Limits

- The search space leaves out query transformers and the generation model (P5 and later).
- A held-out part of a dozen questions cannot confirm a small gain; the report states how small a difference it can detect.
- Indexing a new chunking and model for every document costs time on a laptop: the planner shows what is missing before the search starts.
