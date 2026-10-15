package dev.glassbox.autotune;

/**
 * The quality score AutoTune optimises (SPEC 9): a transparent weighted blend, by default 50% context recall, 30% correctness and 20% faithfulness.
 * Every component is also reported on its own. When a component is not available for a question (a retrieval-only run has no answer; an answer that
 * made no claims has no faithfulness) the weights of the others are scaled up to sum to one, so a missing component never counts as zero.
 */
public final class Quality {
    public static final double W_CONTEXT_RECALL = 0.5;
    public static final double W_CORRECTNESS = 0.3;
    public static final double W_FAITHFULNESS = 0.2;

    private Quality() {}

    /** Null components are left out. Returns NaN when all are missing. */
    public static double blend(Double contextRecall, Double correctness, Double faithfulness) {
        double sum = 0, weight = 0;
        if (contextRecall != null) { sum += W_CONTEXT_RECALL * contextRecall; weight += W_CONTEXT_RECALL; }
        if (correctness != null) { sum += W_CORRECTNESS * correctness; weight += W_CORRECTNESS; }
        if (faithfulness != null) { sum += W_FAITHFULNESS * faithfulness; weight += W_FAITHFULNESS; }
        return weight == 0 ? Double.NaN : sum / weight;
    }
}
