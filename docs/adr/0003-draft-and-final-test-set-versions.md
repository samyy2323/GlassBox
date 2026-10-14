# ADR 0003: Test sets are DRAFT while reviewed and FINAL when frozen

Status: accepted (2026-10-14).

SPEC 7 says "a test set version is immutable, any edit creates a new version with a parent pointer". SPEC 11 also describes a
review queue where a person accepts, rejects and edits generated questions. Taken literally, every click in the queue would
create a new version.

Decision: a version has a lifecycle.

- `GENERATING`: the background job is writing questions (each is saved as soon as it exists, so a crash resumes instead of restarting).
- `DRAFT`: the version reviewers work on. Questions can be accepted, rejected, edited and added.
- `FINAL`: frozen by `finalize`. Every write is refused with HTTP 409. Experiments (P3) may only use `FINAL` versions, and within one only `ACCEPTED` and `EDITED` questions.
- Changing a final version means `POST /api/testsets/{id}/versions`: a new `DRAFT` copy with `parent_version_id` set. Rejected questions are not copied; the rest keep their status.
- `FAILED`: generation produced nothing usable; the reason is stored.

Every question has a `stable_id` that survives copying, so `GET /api/testsets/{id}/diff?against=` can tell a question that was
added, removed or edited from one that is unchanged. Rejected questions are ignored by the diff (they are not part of the version's content).

Gold evidence is a character span of one document version's normalized text, `{documentId, versionId, charStart, charEnd}`, with a text
snippet kept for display. It is never a chunk id, so it stays valid when the chunking changes. The span is chosen deterministically
inside the sampled chunk: the sentence (or up to three neighbouring sentences) that best covers the words of the expected answer.
The model is not asked to quote, because a 3B model rarely copies text exactly, and a span that is not literally in the document is useless as gold.

Quality filters flag, they do not drop. A small model writes some weak questions, and the share a person accepts is itself a result worth reporting:

| Flag | Meaning | How it is decided |
| --- | --- | --- |
| `COPY_RISK` | repeats the evidence | more than 70% of the question's words occur in the evidence |
| `AMBIGUOUS` | not self-contained | wording such as "the document", or a model answering NO to "does this make sense without the source?" |
| `LOW_ANSWERABILITY` | not answerable from the evidence alone | a model given only the evidence does not reproduce the expected answer (shares under 35% of its meaningful words) |
| `DUPLICATE` | near-identical to an earlier question | embedding cosine similarity above 0.92 |

The judge for answerability is lexical agreement with the expected answer. A model-based judge arrives with the evaluation metrics in P3.
The two model checks cost two extra calls per question and can be switched off with `GLASSBOX_TESTSET_LLM_FILTERS=false`.

Acceptance rate is (accepted + edited) / (accepted + edited + rejected), over reviewed questions only.

Tests: `TestSetApiTest` (generation, flags, resume after a model outage, review, immutability, versioning, diff, roles, tenant isolation),
`DatasetUnitTest` (evidence locator, filters, sampler, reply parsing), and the RLS isolation suite, which fails the build if either table loses its policy.
