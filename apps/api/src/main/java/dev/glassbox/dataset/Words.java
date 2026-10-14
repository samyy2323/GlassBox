package dev.glassbox.dataset;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small lexical helpers shared by the evidence locator and the quality filters. */
final class Words {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Set<String> STOP = Set.of("a", "an", "the", "of", "in", "on", "at", "to", "for", "and", "or", "is", "are", "was",
            "were", "be", "been", "by", "with", "as", "that", "this", "it", "its", "from", "which", "what", "who", "how", "when",
            "where", "why", "do", "does", "did", "than", "then", "into", "per", "any", "all");

    private Words() {}

    /** Lower-cased words of a text, in order, without duplicates. */
    static Set<String> all(String text) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Like {@link #all} without stop words and one-letter words: the words that carry meaning. */
    static Set<String> content(String text) {
        Set<String> out = all(text);
        out.removeIf(w -> STOP.contains(w) || (w.length() < 2 && !Character.isDigit(w.charAt(0))));
        return out;
    }

    /** Share of {@code of} that also occurs in {@code in}. */
    static double overlap(Set<String> of, Set<String> in) {
        if (of.isEmpty()) return 0;
        long hit = of.stream().filter(in::contains).count();
        return (double) hit / of.size();
    }
}
