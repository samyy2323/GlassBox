package dev.glassbox.retrieval.transform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Reads a model's reply as one query per line, tolerating bullets, numbering, quotes and a lead-in sentence. */
final class ReplyLines {
    private static final Pattern LEAD = Pattern.compile("^\\s*(?:[-*•]+|\\d+[.)]|\\(\\d+\\))\\s*");
    private static final Pattern PREAMBLE = Pattern.compile("^(here are|here is|sure|okay|paraphrases|sub-?questions|queries)\\b.*:\\s*$", Pattern.CASE_INSENSITIVE);

    private ReplyLines() {}

    /** At most {@code max} distinct lines, none equal (ignoring case and punctuation) to {@code original}, none longer than 300 characters. */
    static List<String> parse(String reply, String original, int max) {
        if (reply == null) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        seen.add(norm(original));
        List<String> out = new ArrayList<>();
        for (String raw : reply.split("\\R")) {
            String line = LEAD.matcher(raw.strip()).replaceFirst("").strip();
            if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) line = line.substring(1, line.length() - 1).strip();
            if (line.length() < 3 || line.length() > 300 || PREAMBLE.matcher(line).matches()) continue;
            if (!seen.add(norm(line))) continue;
            out.add(line);
            if (out.size() >= max) break;
        }
        return out;
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }
}
