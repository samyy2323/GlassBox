package dev.glassbox.diagnose;

import dev.glassbox.diagnose.FailureClassifier.Diagnosis;
import dev.glassbox.diagnose.FailureClassifier.Input;
import dev.glassbox.diagnose.FailureClassifier.SpanTrace;
import dev.glassbox.eval.RetrievalMetrics;
import dev.glassbox.trace.TraceModel.CandidateView;
import dev.glassbox.trace.TraceModel.Disposition;
import dev.glassbox.trace.TraceModel.Status;
import dev.glassbox.trace.TraceModel.Trace;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the failure classifier on a stored chat trace. A trace holds what the pipeline did but not what it should have found, so the evidence has to be
 * marked by a person; without it the classifier has nothing to compare with and only the trace's own warnings can be shown ({@link #warnings}).
 *
 * What is known about a marked passage: it was a candidate if some retrieved chunk covers at least half of it; it was ranked if such a chunk was
 * among the top n; it reached the prompt if such a chunk was in the context. What is not known is whether the answer is correct, so a trace whose evidence
 * reached the prompt is reported as "retrieval was fine" and the answer itself is the next suspect, never as a verdict.
 */
public final class TraceDiagnosis {
    /** A passage a person marked as the evidence, with the best share of it any single chunk covers (null when not looked up). */
    public record Marked(UUID documentId, int charStart, int charEnd, Double bestCover) {}

    public record Result(Diagnosis diagnosis, String message, List<String> warnings) {}

    private TraceDiagnosis() {}

    /** The classifier input for a trace and the passages marked as evidence (empty means: no evidence to compare with). */
    public static Input input(Trace t, List<Marked> marked) {
        List<SpanTrace> spans = new ArrayList<>();
        for (Marked m : marked) {
            var gold = new RetrievalMetrics.Gold(m.documentId(), m.charStart(), m.charEnd());
            boolean inCandidates = false, inRanked = false, inContext = false;
            for (CandidateView c : t.data().candidates()) {
                if (!RetrievalMetrics.hits(new RetrievalMetrics.Ref(c.documentId(), c.charStart(), c.charEnd()), gold)) continue;
                inCandidates = true;
                if (c.disposition() == Disposition.IN_CONTEXT || c.disposition() == Disposition.DROPPED) inRanked = true;
                if (c.disposition() == Disposition.IN_CONTEXT) inContext = true;
            }
            spans.add(new SpanTrace(inContext ? null : m.bestCover(), inCandidates, inRanked, inContext));
        }
        return new Input("FACTUAL", spans, t.status() != Status.ERROR && t.status() != Status.CANCELLED, t.status() == Status.ABSTAINED, null, null, false);
    }

    public static Result diagnose(Trace t, List<Marked> marked) {
        List<String> warnings = warnings(t);
        if (marked.isEmpty()) return new Result(null, "Mark the passage that holds the answer to see where the pipeline lost it.", warnings);
        Diagnosis d = FailureClassifier.classify(input(t, marked));
        return new Result(d, explain(d, t.status() == Status.ABSTAINED), warnings);
    }

    /** Things the trace itself shows, with no evidence needed. */
    public static List<String> warnings(Trace t) {
        List<String> w = new ArrayList<>();
        if (t.status() == Status.ABSTAINED) w.add("The system said the answer is not in the documents.");
        if (t.status() == Status.ERROR) w.add("The answer failed with an error" + (t.data().error() == null ? "." : ": " + t.data().error()));
        if (t.data().candidates().isEmpty()) w.add("Nothing was retrieved for this question.");
        if (t.data().unknownCitations() != null && !t.data().unknownCitations().isEmpty()) {
            w.add("The answer cites " + String.join(", ", t.data().unknownCitations()) + ", which were not in the prompt.");
        }
        if (t.status() == Status.OK && t.data().citations() != null && t.data().citations().isEmpty() && t.answer() != null && !t.answer().isBlank()) {
            w.add("The answer cites no source.");
        }
        return w;
    }

    static String explain(Diagnosis d, boolean abstained) {
        if (d == null) {
            return abstained ? "The evidence reached the prompt, yet the system declined to answer: the model refused although the answer was there."
                    : "The evidence reached the prompt, so retrieval was not the problem. Whether the answer is wrong cannot be told from the trace; read the answer against the passage.";
        }
        return switch (d.stage()) {
            case CHUNKING_MISS -> "No single chunk holds this passage, so no search could have returned it whole. Larger chunks or a different chunker would.";
            case RETRIEVAL_MISS -> "A chunk holds the passage but the search did not return it. Hybrid search, a better embedding model or a query transformer may find it.";
            case RANKING_MISS -> "The search returned the passage, but below the chunks that went into the prompt. A reranker or a larger top n would help.";
            case CONTEXT_MISS -> "The passage was in the top chunks but was cut by the token budget or removed as a duplicate. A larger budget would keep it.";
            case PARTIAL_RETRIEVAL -> "Only some of the marked passages reached the prompt (the rest were lost at " + d.lostAt().name().toLowerCase().replace('_', ' ') + ").";
            default -> "The evidence reached the prompt.";
        };
    }

    /** Whether the diagnosis points at the pipeline (true) or at the answer being wrong with the evidence present (false). */
    public static boolean retrievalProblem(Diagnosis d) {
        return d != null && Set.of(FailureClassifier.Stage.CHUNKING_MISS, FailureClassifier.Stage.RETRIEVAL_MISS, FailureClassifier.Stage.RANKING_MISS,
                FailureClassifier.Stage.CONTEXT_MISS, FailureClassifier.Stage.PARTIAL_RETRIEVAL).contains(d.stage());
    }
}
