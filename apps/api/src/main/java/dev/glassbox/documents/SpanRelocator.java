package dev.glassbox.documents;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a gold span of the old version of a document in the new one (SPEC 11): first the exact text (the occurrence nearest to where it was), then the
 * same words with different spacing, then a passage of the new text that shares almost all of its words. A span that cannot be found is not guessed at:
 * the question is marked stale and goes back to a person.
 */
public final class SpanRelocator {
    public enum How { EXACT, WHITESPACE, FUZZY }

    public record Located(int start, int end, How how) {}

    /** Share of the words that the new passage must have in common with the old span (Jaccard) to count as the same passage. */
    public static final double FUZZY_THRESHOLD = 0.75;

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\R+");

    private SpanRelocator() {}

    public static Optional<Located> relocate(String oldText, String newText, int start, int end) {
        if (start < 0 || end > oldText.length() || end <= start) return Optional.empty();
        String span = oldText.substring(start, end);
        if (span.isBlank()) return Optional.empty();

        int best = -1;
        for (int at = newText.indexOf(span); at >= 0; at = newText.indexOf(span, at + 1)) {
            if (best < 0 || Math.abs(at - start) < Math.abs(best - start)) best = at;
        }
        if (best >= 0) return Optional.of(new Located(best, best + span.length(), How.EXACT));

        List<String> words = new ArrayList<>();
        Matcher w = Pattern.compile("\\S+").matcher(span);
        while (w.find()) words.add(Pattern.quote(w.group()));
        Matcher spaced = Pattern.compile(String.join("\\s+", words)).matcher(newText);
        Located nearest = null;
        while (spaced.find()) {
            if (nearest == null || Math.abs(spaced.start() - start) < Math.abs(nearest.start() - start)) nearest = new Located(spaced.start(), spaced.end(), How.WHITESPACE);
        }
        if (nearest != null) return Optional.of(nearest);
        return fuzzy(newText, span, start);
    }

    private static Optional<Located> fuzzy(String newText, String span, int oldStart) {
        Set<String> want = words(span);
        if (want.size() < 3) return Optional.empty(); // too short to tell one passage from another
        List<int[]> sentences = new ArrayList<>();
        Matcher m = SENTENCE_END.matcher(newText);
        int from = 0;
        while (m.find()) {
            if (m.start() > from) sentences.add(new int[] {from, m.start()});
            from = m.end();
        }
        if (from < newText.length()) sentences.add(new int[] {from, newText.length()});

        double bestScore = 0;
        Located best = null;
        for (int i = 0; i < sentences.size(); i++) {
            for (int j = i; j < sentences.size(); j++) {
                int s = sentences.get(i)[0], e = sentences.get(j)[1];
                if (e - s > span.length() * 1.6 + 20) break;
                double score = jaccard(want, words(newText.substring(s, e)));
                boolean better = score > bestScore + 1e-9 || (Math.abs(score - bestScore) <= 1e-9 && best != null && Math.abs(s - oldStart) < Math.abs(best.start() - oldStart));
                if (score >= FUZZY_THRESHOLD && better) {
                    bestScore = score;
                    best = new Located(s, e, How.FUZZY);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int common = 0;
        for (String x : a) if (b.contains(x)) common++;
        return (double) common / (a.size() + b.size() - common);
    }
}
