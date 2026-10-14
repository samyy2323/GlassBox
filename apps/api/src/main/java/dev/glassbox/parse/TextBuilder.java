package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Block;
import dev.glassbox.parse.ParsedDocument.Kind;
import java.util.ArrayList;
import java.util.List;

/** Assembles the normalized text and block list from parser callbacks. */
public final class TextBuilder {
    private static final String SEP = "\n\n";

    private final StringBuilder sb = new StringBuilder();
    private final List<Block> blocks = new ArrayList<>();
    private final List<Integer> pageStarts = new ArrayList<>();
    private final List<int[]> levels = new ArrayList<>(); // heading levels on the stack
    private final List<String> titles = new ArrayList<>();

    public void startPage() {
        pageStarts.add(sb.isEmpty() ? 0 : sb.length() + SEP.length());
    }

    public void heading(int level, String raw) {
        String t = clean(raw);
        if (t.isEmpty()) return;
        while (!levels.isEmpty() && levels.get(levels.size() - 1)[0] >= level) {
            levels.remove(levels.size() - 1);
            titles.remove(titles.size() - 1);
        }
        levels.add(new int[] {level});
        titles.add(t);
        append(Kind.HEADING, level, t);
    }

    public void block(Kind kind, String raw) {
        String t = clean(raw);
        if (!t.isEmpty()) append(kind, 0, t);
    }

    private void append(Kind kind, int level, String t) {
        if (!sb.isEmpty()) sb.append(SEP);
        int start = sb.length();
        sb.append(t);
        blocks.add(new Block(kind, level, start, sb.length(), List.copyOf(titles)));
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public ParsedDocument build() {
        return new ParsedDocument(sb.toString(), List.copyOf(blocks), pageStarts.stream().mapToInt(Integer::intValue).toArray());
    }

    /** Collapses whitespace runs so offsets are stable across parsers. */
    static String clean(String s) {
        return s == null ? "" : s.replace(' ', ' ').replaceAll("\\s+", " ").strip();
    }
}
