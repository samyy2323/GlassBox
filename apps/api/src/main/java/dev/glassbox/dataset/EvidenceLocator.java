package dev.glassbox.dataset;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses the tight gold span inside a sampled chunk: the sentence (or up to three neighbouring sentences) that best covers
 * the words of the expected answer. Chosen by overlap rather than by asking the model to quote, because a small model rarely
 * copies text exactly, and a span that is not literally in the document would be worthless as gold.
 */
public final class EvidenceLocator {
    private static final Pattern BOUNDARY = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}\\p{N}\"'(\\[])|\\n\\s*\\n");
    private static final int MAX_SENTENCES = 3;
    /** Adding a neighbouring sentence must cover at least this much more of the answer to be worth the extra words. */
    private static final double MIN_GAIN = 0.15;

    private EvidenceLocator() {}

    /** A half-open character range of the document text. */
    public record Range(int start, int end) {}

    /** A chosen range and how much of the answer's meaning it holds: the share of the answer's content words found in it. */
    public record Located(Range range, double coverage) {}

    /**
     * @param documentText normalized text of the document version
     * @param from         chunk start (inclusive)
     * @param to           chunk end (exclusive)
     */
    public static Optional<Range> locate(String documentText, int from, int to, String expectedAnswer, String question) {
        return locateWithCoverage(documentText, from, to, expectedAnswer, question).map(Located::range);
    }

    /** Like {@link #locate}, also saying how well the range covers the answer, so callers can tell evidence from a mere best guess. */
    public static Optional<Located> locateWithCoverage(String documentText, int from, int to, String expectedAnswer, String question) {
        List<Range> sentences = sentences(documentText, from, to);
        if (sentences.isEmpty()) return Optional.empty();

        Set<String> target = Words.content(expectedAnswer);
        if (target.isEmpty()) target = Words.content(question);

        int best = 0;
        double bestCoverage = -1;
        int bestLen = Integer.MAX_VALUE;
        for (int i = 0; i < sentences.size(); i++) {
            Range r = sentences.get(i);
            double coverage = Words.overlap(target, Words.all(documentText.substring(r.start(), r.end())));
            int len = r.end() - r.start();
            if (coverage > bestCoverage || (coverage == bestCoverage && len < bestLen)) {
                best = i;
                bestCoverage = coverage;
                bestLen = len;
            }
        }

        int lo = best, hi = best;
        while (hi - lo + 1 < MAX_SENTENCES && bestCoverage < 1.0) {
            double current = coverage(documentText, sentences, lo, hi, target);
            double gainLeft = lo > 0 ? coverage(documentText, sentences, lo - 1, hi, target) - current : -1;
            double gainRight = hi + 1 < sentences.size() ? coverage(documentText, sentences, lo, hi + 1, target) - current : -1;
            if (Math.max(gainLeft, gainRight) < MIN_GAIN) break;
            if (gainLeft >= gainRight) lo--; else hi++;
            bestCoverage = coverage(documentText, sentences, lo, hi, target);
        }
        Range range = new Range(sentences.get(lo).start(), sentences.get(hi).end());
        return Optional.of(new Located(range, Words.overlap(target, Words.all(documentText.substring(range.start(), range.end())))));
    }

    private static double coverage(String text, List<Range> s, int lo, int hi, Set<String> target) {
        return Words.overlap(target, Words.all(text.substring(s.get(lo).start(), s.get(hi).end())));
    }

    /** Sentence ranges inside [from, to), trimmed of surrounding whitespace, never empty. */
    static List<Range> sentences(String text, int from, int to) {
        int end = Math.min(to, text.length());
        List<Range> out = new ArrayList<>();
        if (from >= end) return out;
        Matcher m = BOUNDARY.matcher(text).region(from, end);
        int start = from;
        while (m.find()) {
            addTrimmed(text, start, m.start(), out);
            start = m.end();
        }
        addTrimmed(text, start, end, out);
        return out;
    }

    private static void addTrimmed(String text, int s, int e, List<Range> out) {
        while (s < e && Character.isWhitespace(text.charAt(s))) s++;
        while (e > s && Character.isWhitespace(text.charAt(e - 1))) e--;
        if (e > s) out.add(new Range(s, e));
    }
}
