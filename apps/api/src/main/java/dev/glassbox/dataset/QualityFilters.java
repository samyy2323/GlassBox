package dev.glassbox.dataset;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The deterministic quality checks of SPEC 7. They only flag; nothing is dropped silently, because the acceptance rate of
 * generated questions is itself a number worth reporting. The two model-based checks live in {@link LlmChecks}.
 */
public final class QualityFilters {
    public static final String COPY_RISK = "COPY_RISK";
    public static final String AMBIGUOUS = "AMBIGUOUS";
    public static final String LOW_ANSWERABILITY = "LOW_ANSWERABILITY";
    public static final String DUPLICATE = "DUPLICATE";
    /** An UNANSWERABLE question that a model answered from what the pipeline retrieved: the corpus may in fact answer it. */
    public static final String ANSWERABLE_RISK = "ANSWERABLE_RISK";
    public static final List<String> ALL = List.of(COPY_RISK, AMBIGUOUS, LOW_ANSWERABILITY, DUPLICATE, ANSWERABLE_RISK);

    /** More than this share of the question's words found in the evidence makes keyword search look better than it is. */
    public static final double COPY_THRESHOLD = 0.7;
    public static final double DUPLICATE_THRESHOLD = 0.92;
    /** Minimum word overlap between the model's answer from the evidence alone and the expected answer. */
    public static final double ANSWER_AGREEMENT = 0.35;

    private static final Pattern DEICTIC = Pattern.compile(
            "\\b(the|this|that|these|those)\\s+(document|documents|text|passage|passages|excerpt|paragraph|section|article|report|file|"
                    + "context|page|source|author)\\b|\\b(above|following|given)\\s+(text|passage|excerpt|paragraph|context)\\b|"
                    + "\\baccording to (it|them)\\b|\\bmentioned (above|earlier|here)\\b", Pattern.CASE_INSENSITIVE);

    private QualityFilters() {}

    /** Share of the question's distinct words that also occur in the evidence text. */
    public static double lexicalOverlap(String question, String evidence) {
        return Words.overlap(Words.all(question), Words.all(evidence));
    }

    public static boolean copyRisk(String question, List<String> evidence) {
        return lexicalOverlap(question, String.join(" ", evidence)) > COPY_THRESHOLD;
    }

    /** Refers to "the document", "the passage" and so on: it only makes sense next to the source. */
    public static boolean deictic(String question) {
        return DEICTIC.matcher(question).find();
    }

    /** Share of the expected answer's meaningful words that the model's own answer also contains. */
    public static double answerAgreement(String expected, String produced) {
        Set<String> want = Words.content(expected);
        if (want.isEmpty()) want = Words.all(expected);
        return Words.overlap(want, Words.all(produced.toLowerCase(Locale.ROOT)));
    }

    /** True when a model's reply says the text does not contain the answer (the answer prompt's refusal is "NOT STATED"). */
    public static boolean saysNotStated(String reply) {
        if (reply == null) return false;
        String r = reply.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z ]", " ").replaceAll("\\s+", " ").strip();
        return r.startsWith("NOT STATED") || r.startsWith("THE TEXT DOES NOT") || r.startsWith("NO ANSWER");
    }

    public static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public static boolean duplicate(float[] candidate, List<float[]> earlier) {
        for (float[] v : earlier) if (cosine(candidate, v) > DUPLICATE_THRESHOLD) return true;
        return false;
    }
}
