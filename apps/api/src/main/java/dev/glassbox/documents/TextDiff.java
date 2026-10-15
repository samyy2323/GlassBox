package dev.glassbox.documents;

import java.util.ArrayList;
import java.util.List;

/** A paragraph-level diff of two versions of a document (SPEC 11): which paragraphs were kept, added and removed, in reading order. Longest common subsequence. */
public final class TextDiff {
    public enum Kind { SAME, ADDED, REMOVED }

    public record Op(Kind kind, String text) {}

    public record Summary(int same, int added, int removed) {}

    private TextDiff() {}

    /** Paragraphs are separated by blank lines; surrounding whitespace is ignored, empty paragraphs are not paragraphs. */
    public static List<String> paragraphs(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String p : text.split("\\R\\s*\\R")) {
            String t = p.strip().replaceAll("[ \\t]+", " ");
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public static List<Op> diff(String before, String after) {
        List<String> a = paragraphs(before), b = paragraphs(after);
        int n = a.size(), m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>();
        int i = 0, j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                ops.add(new Op(Kind.SAME, a.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op(Kind.REMOVED, a.get(i++)));
            } else {
                ops.add(new Op(Kind.ADDED, b.get(j++)));
            }
        }
        while (i < n) ops.add(new Op(Kind.REMOVED, a.get(i++)));
        while (j < m) ops.add(new Op(Kind.ADDED, b.get(j++)));
        return ops;
    }

    public static Summary summarize(List<Op> ops) {
        int same = 0, added = 0, removed = 0;
        for (Op o : ops) {
            switch (o.kind()) {
                case SAME -> same++;
                case ADDED -> added++;
                case REMOVED -> removed++;
            }
        }
        return new Summary(same, added, removed);
    }
}
