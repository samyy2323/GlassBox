package dev.glassbox.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Every metric an experiment can report, in display order, and which of them are pass/fail or depend on a model's judgement. */
public final class Metrics {
    public static final String FAITHFULNESS = "faithfulness";
    public static final String CORRECTNESS = "correctness";
    public static final String RELEVANCE = "relevance";
    public static final String CITATION_PRECISION = "citation_precision";
    /** On answerable questions: 1 when the system wrongly said "not in the documents". Lower is better. */
    public static final String FALSE_ABSTENTION = "false_abstention";
    /** On unanswerable questions: 1 when the system correctly said it does not know. */
    public static final String ABSTENTION_ACCURACY = "abstention_accuracy";

    public static final List<String> GENERATION = List.of(FAITHFULNESS, CORRECTNESS, RELEVANCE, CITATION_PRECISION, FALSE_ABSTENTION, ABSTENTION_ACCURACY);

    public static final List<String> ALL;

    static {
        List<String> all = new ArrayList<>(RetrievalMetrics.ALL);
        all.addAll(GENERATION);
        ALL = List.copyOf(all);
    }

    /** Pass/fail per question: compared with the exact McNemar test. */
    public static final Set<String> BINARY;

    static {
        Set<String> binary = new java.util.HashSet<>(RetrievalMetrics.BINARY);
        binary.add(FALSE_ABSTENTION);
        binary.add(ABSTENTION_ACCURACY);
        BINARY = Set.copyOf(binary);
    }

    /** Metrics that rest on an LLM judge or on claim verification: they carry the "uncalibrated" badge until the judge is calibrated. */
    public static final Set<String> JUDGED = Set.of(FAITHFULNESS, CORRECTNESS, RELEVANCE, CITATION_PRECISION);

    private Metrics() {}
}
