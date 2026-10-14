package dev.glassbox.eval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sentence splitting that keeps decimals ("4.5 mm") together and keeps [c1] citation markers with their sentence. */
public final class Sentences {
    private static final Pattern CITATION = Pattern.compile("\\s*\\[c\\d+(?:\\s*,\\s*c\\d+)*\\]");
    private static final Pattern LABELS = Pattern.compile("\\[(c\\d+(?:\\s*,\\s*c\\d+)*)\\]");

    private Sentences() {}

    /** A sentence ends at . ! or ? followed by whitespace or the end (after any citation markers), and at a line break. */
    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int i = 0, n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                flush(sb, out);
                i++;
                continue;
            }
            sb.append(ch);
            i++;
            if (ch == '.' || ch == '!' || ch == '?') {
                while (i < n && (text.charAt(i) == '.' || text.charAt(i) == '!' || text.charAt(i) == '?')) sb.append(text.charAt(i++));
                Matcher m = CITATION.matcher(text).region(i, n);
                while (m.lookingAt()) {
                    sb.append(text, m.start(), m.end());
                    i = m.end();
                    m.region(i, n);
                }
                if (i >= n || Character.isWhitespace(text.charAt(i))) flush(sb, out);
            }
        }
        flush(sb, out);
        return out;
    }

    private static void flush(StringBuilder sb, List<String> out) {
        String s = sb.toString().strip();
        sb.setLength(0);
        if (!s.isEmpty()) out.add(s);
    }

    /** The citation labels used in a sentence, in order, without repeats. */
    public static List<String> labels(String sentence) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = LABELS.matcher(sentence);
        while (m.find()) for (String l : m.group(1).split("\\s*,\\s*")) out.add(l);
        return new ArrayList<>(out);
    }

    public static String stripLabels(String sentence) {
        return LABELS.matcher(sentence).replaceAll("").replaceAll("\\s+", " ").replaceAll("\\s+([.!?,;:])", "$1").strip();
    }
}
