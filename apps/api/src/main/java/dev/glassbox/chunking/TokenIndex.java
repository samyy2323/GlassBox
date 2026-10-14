package dev.glassbox.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic tokenizer with exact character offsets: a token is a run of letters/digits or a single
 * punctuation mark. It approximates (and is usually somewhat below) a BPE token count; using one
 * dependency-free definition everywhere keeps chunk sizes, budgets and tests consistent.
 */
public final class TokenIndex {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+|[^\\s\\p{L}\\p{N}_]");

    private final int[] starts;
    private final int[] ends;

    public TokenIndex(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        while (m.find()) spans.add(new int[] {m.start(), m.end()});
        starts = new int[spans.size()];
        ends = new int[spans.size()];
        for (int i = 0; i < spans.size(); i++) {
            starts[i] = spans.get(i)[0];
            ends[i] = spans.get(i)[1];
        }
    }

    public static int count(String text) {
        int n = 0;
        Matcher m = TOKEN.matcher(text);
        while (m.find()) n++;
        return n;
    }

    public int size() {
        return starts.length;
    }

    public int start(int token) {
        return starts[token];
    }

    public int end(int token) {
        return ends[token];
    }

    /** Index of the first token starting at or after the offset. */
    public int firstAtOrAfter(int offset) {
        int lo = 0, hi = starts.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (starts[mid] >= offset) hi = mid; else lo = mid + 1;
        }
        return lo;
    }

    /** Number of tokens lying inside [from, to). */
    public int count(int from, int to) {
        int first = firstAtOrAfter(from);
        int last = firstAtOrAfter(to); // first token starting at or after 'to'
        return Math.max(0, last - first);
    }
}
