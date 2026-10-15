package dev.glassbox.retrieval.transform;

/**
 * Query transformers (SPEC 6). Each one costs model calls before the search, so each has a cost entry used by the estimators:
 * the calls and the rough tokens (prompt plus reply) it adds to every query.
 */
public enum Transformer {
    NONE(0, 0),
    /** Three paraphrases, each searched, results fused with RRF. Helps short or vague questions. */
    MULTI_QUERY(1, 280),
    /** A hypothetical answer is embedded instead of the question. Helps vocabulary mismatch and comparisons. */
    HYDE(1, 320),
    /** A multi-part question is split into sub-questions, each searched, results fused. Helps multi-hop questions. */
    DECOMPOSE(1, 260);

    private final int llmCalls;
    private final int tokens;

    Transformer(int llmCalls, int tokens) {
        this.llmCalls = llmCalls;
        this.tokens = tokens;
    }

    public int llmCalls() {
        return llmCalls;
    }

    public int tokens() {
        return tokens;
    }
}
