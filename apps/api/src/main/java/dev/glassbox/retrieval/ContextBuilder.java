package dev.glassbox.retrieval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Packs ranked chunks into the prompt's token budget (SPEC 6). Every chunk that does not make it is recorded with the
 * reason, because the failure taxonomy (CONTEXT_MISS) and X-Ray both need to know what was dropped and why.
 */
@Component
public class ContextBuilder {
    public enum Reason { DUPLICATE, OVERLAP, BUDGET }

    /** {@code label} is the citation id the model is told to use, e.g. "c1". */
    public record ContextChunk(String label, Candidate chunk) {}

    public record Dropped(Candidate chunk, Reason reason, String detail) {}

    public record Context(List<ContextChunk> included, List<Dropped> dropped, int tokensUsed, int budget) {}

    /** A chunk is redundant when this share of the smaller of the two spans is already covered. */
    static final double OVERLAP_LIMIT = 0.5;

    public Context build(List<Candidate> ranked, int tokenBudget) {
        List<ContextChunk> included = new ArrayList<>();
        List<Dropped> dropped = new ArrayList<>();
        Set<String> seenText = new HashSet<>();
        int used = 0;
        for (Candidate c : ranked) {
            String norm = c.text().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
            if (!seenText.add(norm)) {
                dropped.add(new Dropped(c, Reason.DUPLICATE, "identical text to a chunk already in the context"));
                continue;
            }
            ContextChunk overlapping = included.stream().filter(i -> overlapShare(i.chunk(), c) > OVERLAP_LIMIT).findFirst().orElse(null);
            if (overlapping != null) {
                dropped.add(new Dropped(c, Reason.OVERLAP, "overlaps " + overlapping.label() + " by more than " + (int) (OVERLAP_LIMIT * 100) + "%"));
                continue;
            }
            if (used + c.tokenCount() > tokenBudget) {
                dropped.add(new Dropped(c, Reason.BUDGET, (used + c.tokenCount()) + " tokens would exceed the budget of " + tokenBudget));
                continue; // a smaller, lower-ranked chunk may still fit
            }
            included.add(new ContextChunk("c" + (included.size() + 1), c));
            used += c.tokenCount();
        }
        return new Context(List.copyOf(included), List.copyOf(dropped), used, tokenBudget);
    }

    private static double overlapShare(Candidate a, Candidate b) {
        int smaller = Math.min(a.charEnd() - a.charStart(), b.charEnd() - b.charStart());
        return smaller <= 0 ? 0 : (double) a.overlapWith(b) / smaller;
    }
}
