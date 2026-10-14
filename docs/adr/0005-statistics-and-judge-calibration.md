# ADR 0005: How the Eval Lab decides a difference is real, and when a judge may be trusted

Status: accepted (2026-10-14).

A pass rate on a test set of a few dozen questions moves by several points from chance alone, and an LLM judge can be wrong in ways
that look like data. The Eval Lab therefore reports every number with its uncertainty and treats judged numbers as unproven until measured.

## Statistics

All statistics are computed from **per-question score vectors** by the `dev.glassbox.stats` package, which has no framework dependencies.
Every run is scored on the same questions, so comparisons are paired.

| What | Method |
| --- | --- |
| Interval on a metric | percentile bootstrap over questions, 10,000 resamples, fixed seed, 95% |
| Interval by source document | cluster bootstrap (whole documents are resampled, because questions from one document resemble each other); shown beside the question-level interval |
| Difference between two runs | per-question differences (B minus A), bootstrap interval on their mean |
| Significance, pass/fail metrics | exact McNemar test: only the questions that changed sides matter, each is a fair coin under "no difference" |
| Significance, other metrics | paired sign-flip permutation test on the differences: exact (all 2^n flips) up to 20 questions, 100,000 sampled flips above |
| Many configurations | Holm-Bonferroni across the configurations compared with one baseline, **one metric at a time** |
| Smallest detectable difference | (1.96 + 0.84) x sd(differences) / sqrt(n): shown so a flat result is read as "this test set cannot tell", not "no effect" |

A difference is called real only when **both** the interval excludes zero **and** the corrected p-value is below 0.05. The sentence shown is
"B beats A by +4.1 points, 95% CI [+0.8, +7.5], p=0.02" or "No significant difference (CI [-2.1, +5.0], p=0.40)". The interval is not itself
corrected for multiple comparisons, so with several configurations an interval can exclude zero while the verdict is still "no significant
difference": that is the correction doing its job, and the page says the p-value was corrected.

**Why Holm is per metric, not across the seven metrics of one comparison.** The first version corrected over all metrics of a comparison. The
metrics (Recall@5, nDCG, context recall, ...) describe the same retrieval, so they are not seven independent questions, and correcting
across them turned a clear 20-point gain (exact p = 0.008) into "not significant" (0.008 x 7 = 0.055). SPEC 8 asks for correction "when many
configs are compared", which is what the per-metric family does. `EvalUnitTest.theSevenMetricsOfOneComparisonAreNotCorrectedAgainstEachOther` pins it.

**How the code is checked.** `benchmarks/stats_fixtures.py` computes reference values with SciPy, NumPy and scikit-learn and writes
`apps/api/src/test/resources/stats-fixtures.json`; `StatsFixtureTest` checks the Java against it. McNemar, the permutation test (n up to 16, where
SciPy enumerates every flip too), percentiles, Cohen's kappa, precision and recall match to 1e-9 or better. Bootstrap intervals can only agree
within Monte Carlo error (checked to 0.01, where two independent runs differ by about 0.002). SciPy has no cluster bootstrap and no Holm
correction, so those two are checked against short NumPy implementations of the textbook definitions, not against SciPy; the fixture file says so.

## Retrieval metrics

Gold evidence is a character range of a document version, never a chunk id. A retrieved chunk **hits** a gold span when it overlaps at least half
of the span; a question with several spans (multi-hop, comparison) is fully recalled only when every span is hit. Reported: Recall@5, Recall@10,
MRR, nDCG@10 (binary relevance; the ideal list has one relevant chunk per span), context recall (what reached the prompt), and two pass/fail
versions (evidence in the top 5, evidence in the prompt) used where McNemar applies. A question whose document was replaced since the test set
was written is **stale**: its ranges no longer mean anything, so it is counted and reported but not run.

## Judge calibration

Faithfulness, correctness and relevance rest on a model's judgement. Claim verification uses an NLI model (`cross-encoder/nli-deberta-v3-xsmall`,
in the sidecar) for the clear cases and the LLM judge for the cases in between (entailment 0.1 to 0.8, where a verdict of PARTIAL is possible).
A claim is faithful when **any** chunk that was retrieved supports it, and its citation is judged separately (does the **cited** chunk say it),
so a true claim cited to the wrong chunk counts for faithfulness and against citation precision.

Calibration follows SPEC 8: a sample of graded items, **stratified by the judge's verdict** (a random sample of mostly "supported" claims would say nothing
about "unsupported"), is labelled by a person who is not shown the judge's verdict (the API has no field for it), and the two are compared with
percent agreement, Cohen's kappa, a confusion matrix and per-verdict precision and recall. The result is saved per judge model, rubric version and metric.
A judge is **calibrated** with at least 30 labels and kappa of at least 0.6; **uncalibrated** when measured and short of that (including too few labels);
**unknown** when never measured. Every number a judge produced carries an "uncalibrated" badge until its judge is calibrated, and changing a rubric prompt
means a new rubric version, which starts again at unknown. The page also warns when the judge is the model that wrote the answers.

The unreadable-reply rule: if a judge reply cannot be parsed, the metric is left out for that question rather than guessed, and the stored judge detail
says so, so a missing number is visible instead of silently averaged in.

## Limits

- The same small local model writes and judges. That is a real bias, shown as a warning, not removed.
- Questions from a full run can be sampled (`questionLimit`, a fixed random sample by question id) because answering every question is slow on a CPU; fewer questions mean wider intervals, which the page shows.
- Judge labels are per item; the test set author and the labeller may be the same person.
