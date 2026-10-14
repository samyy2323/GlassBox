package dev.glassbox.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalized form of an uploaded document. {@code text} is the single source of truth for offsets:
 * block, section and chunk offsets all index into it, and gold spans (later phases) will too.
 * {@code pageStarts[i]} is the offset where page i+1 begins; empty for formats without pages.
 */
public record ParsedDocument(String text, List<Block> blocks, int[] pageStarts) {

    public enum Kind { HEADING, PARAGRAPH, LIST_ITEM, TABLE_ROW }

    /** A contiguous range of {@code text}. headingPath includes the block itself when it is a heading. */
    public record Block(Kind kind, int level, int start, int end, List<String> headingPath) {}

    /** A heading plus everything up to the next heading (or the part before the first heading). */
    public record Section(int ordinal, Integer parentOrdinal, int level, List<String> headingPath, int start, int end) {}

    /** Page number (1-based) containing the offset, or null when the format has no pages. */
    public Integer pageAt(int offset) {
        if (pageStarts.length == 0) return null;
        int lo = 0, hi = pageStarts.length - 1, ans = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pageStarts[mid] <= offset) { ans = mid; lo = mid + 1; } else hi = mid - 1;
        }
        return ans + 1;
    }

    public List<Section> sections() {
        List<Section> out = new ArrayList<>();
        List<int[]> stack = new ArrayList<>(); // {level, ordinal}
        int current = -1;
        int sectionStart = 0;
        List<String> path = List.of();
        int level = 0;
        Integer parent = null;
        for (Block b : blocks) {
            if (b.kind() == Kind.HEADING) {
                if (current >= 0 || b.start() > 0) {
                    out.add(new Section(out.size(), parent, level, path, sectionStart, b.start()));
                }
                while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= b.level()) stack.remove(stack.size() - 1);
                parent = stack.isEmpty() ? null : stack.get(stack.size() - 1)[1];
                stack.add(new int[] {b.level(), out.size()});
                current = out.size();
                sectionStart = b.start();
                path = b.headingPath();
                level = b.level();
            }
        }
        if (!blocks.isEmpty()) {
            if (current < 0) {
                out.add(new Section(0, null, 0, List.of(), 0, text.length()));
            } else {
                out.add(new Section(out.size(), parent, level, path, sectionStart, text.length()));
            }
        }
        return out;
    }
}
