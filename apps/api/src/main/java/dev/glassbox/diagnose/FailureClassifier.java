package dev.glassbox.diagnose;

import java.util.List;

/**
 * Failure taxonomy of SPEC 9 as a pure function of facts stored with a result. It is deterministic and rule-based on purpose: the same
 * data always gives the same label, so it can be tested row by row and defended.
 *
 * A question fails when its evidence did not reach the prompt or its answer is not fully correct. The stages are checked in pipeline order
 * and the first that fails is the label:
 * <pre>
 *   CHUNKING_MISS      no single chunk covers 80% of a gold span the prompt lacks
 *   RETRIEVAL_MISS     a chunk covers it, but the search did not return it among the fused candidates
 *   RANKING_MISS       returned, but ranked below the final top n
 *   CONTEXT_MISS       in the top n, but dropped by the token budget or de-duplication
 *   PARTIAL_RETRIEVAL  a multi-span question that got some spans into the prompt and not all (any of the three stages above)
 *   GENERATION_MISS    all the evidence was in the prompt but the answer is wrong (sub-label below)
 *   HALLUCINATED_ANSWER an unanswerable question that was answered
 *   BAD_TEST_QUESTION  the evidence was there and the answer is wrong, but the question itself was flagged as ambiguous or unanswerable from its evidence
 * </pre>
 * CHUNKING_MISS outranks PARTIAL_RETRIEVAL because no later stage can repair a chunking that cannot hold the evidence. For a partial
 * retrieval the stage of the span that was lost is kept in {@link Diagnosis#lostAt()}, which is what a recommendation needs.
 */
public final class FailureClassifier {
    /** A chunk that holds this share of a gold span can carry it into the prompt on its own. */
    public static final double CHUNK_COVERS = 0.80;

    public enum Stage {
        CHUNKING_MISS, RETRIEVAL_MISS, RANKING_MISS, CONTEXT_MISS, PARTIAL_RETRIEVAL, GENERATION_MISS, HALLUCINATED_ANSWER, BAD_TEST_QUESTION
    }

    public enum Sub { UNFAITHFUL, INCOMPLETE, WRONG_REASONING, FALSE_ABSTENTION }

    /**
     * What happened to one gold span. {@code bestCover} is the largest share of the span any single chunk covers (null when not looked up
     * because the span reached the prompt).
     */
    public record SpanTrace(Double bestCover, boolean inCandidates, boolean inRanked, boolean inContext) {
        public static SpanTrace found() {
            return new SpanTrace(null, true, true, true);
        }
    }

    /**
     * @param spans      empty for an unanswerable question
     * @param answered   false for a retrieval-only run (no answer to judge)
     * @param abstained  whether the answer said the documents do not contain it (only when answered)
     * @param correctness 1, 0.5 or 0 from the judge (null when not judged)
     * @param faithfulness supported share of the answer's claims (null when it made none)
     * @param flaggedBad the question carries a quality flag that questions the question itself
     */
    public record Input(String qtype, List<SpanTrace> spans, boolean answered, boolean abstained, Double correctness, Double faithfulness, boolean flaggedBad) {}

    /** {@code lostAt} is only set for PARTIAL_RETRIEVAL: the earliest stage at which a missing span was lost. */
    public record Diagnosis(Stage stage, Sub sub, Stage lostAt) {}

    private FailureClassifier() {}

    /** Null when the question did not fail. */
    public static Diagnosis classify(Input in) {
        if (in.spans().isEmpty()) return unanswerable(in);

        List<SpanTrace> missing = in.spans().stream().filter(s -> !s.inContext()).toList();
        if (!missing.isEmpty()) {
            Stage lost = earliest(missing);
            boolean someFound = missing.size() < in.spans().size();
            if (someFound && lost != Stage.CHUNKING_MISS) return new Diagnosis(Stage.PARTIAL_RETRIEVAL, null, lost);
            return new Diagnosis(lost, null, null);
        }
        if (!in.answered()) return null;
        if (in.abstained()) return generation(in, Sub.FALSE_ABSTENTION);
        if (in.correctness() == null || in.correctness() >= 1.0) return null;
        if (in.flaggedBad()) return new Diagnosis(Stage.BAD_TEST_QUESTION, null, null);
        if (in.faithfulness() != null && in.faithfulness() < 1.0) return generation(in, Sub.UNFAITHFUL);
        if (in.correctness() > 0) return generation(in, Sub.INCOMPLETE);
        return generation(in, Sub.WRONG_REASONING);
    }

    private static Diagnosis generation(Input in, Sub sub) {
        return new Diagnosis(Stage.GENERATION_MISS, sub, null);
    }

    /** An unanswerable question fails only by being answered. A retrieval-only run cannot tell, so it is not a failure there. */
    private static Diagnosis unanswerable(Input in) {
        if (!in.answered() || in.abstained()) return null;
        return new Diagnosis(Stage.HALLUCINATED_ANSWER, null, null);
    }

    /** The first pipeline stage at which any of the missing spans was lost. */
    static Stage earliest(List<SpanTrace> missing) {
        Stage first = null;
        for (SpanTrace s : missing) {
            Stage st = stageOf(s);
            if (first == null || st.ordinal() < first.ordinal()) first = st;
        }
        return first;
    }

    static Stage stageOf(SpanTrace s) {
        if (s.bestCover() != null && s.bestCover() < CHUNK_COVERS) return Stage.CHUNKING_MISS;
        if (!s.inCandidates()) return Stage.RETRIEVAL_MISS;
        if (!s.inRanked()) return Stage.RANKING_MISS;
        return Stage.CONTEXT_MISS;
    }
}
