# ADR 0002: Keyword branch matches ANY question term

Status: accepted (2026-10-14).

`websearch_to_tsquery('english', 'how often are pumps inspected')` means `'often' & 'pump' & 'inspect'`: a chunk must contain
every term. A chunk that says "pumps must be inspected every 90 days" does not contain "often", so the keyword branch of hybrid
search returns nothing for the very kind of question users ask, and "hybrid" silently degrades to vector-only.

Decision: `RetrievalParams.keywordMatch` is `ANY` by default. The question's words are joined with `or` before they reach
`websearch_to_tsquery`, and `ts_rank_cd` ranks chunks that match more of them higher. `ALL` stays available (it is the literal SPEC 6
behaviour) and both are part of the AutoTune search space, so the benchmark (B2) can report which works better on a given corpus.

Test: `HybridRetrieverTest.naturalLanguageQuestionsOnlyMatchKeywordsWithAnyTermSemantics`.
