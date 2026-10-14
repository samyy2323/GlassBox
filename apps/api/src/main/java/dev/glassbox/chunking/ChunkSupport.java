package dev.glassbox.chunking;

import dev.glassbox.chunking.Chunker.ChunkOut;
import dev.glassbox.parse.ParsedDocument;
import dev.glassbox.parse.ParsedDocument.Block;
import dev.glassbox.parse.ParsedDocument.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared range arithmetic for the chunkers. All ranges are [start, end) offsets into the document text. */
final class ChunkSupport {
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    final ParsedDocument doc;
    final TokenIndex tokens;
    final List<ParsedDocument.Section> sections;
    final int maxSize;

    ChunkSupport(ParsedDocument doc, int maxSize) {
        this.doc = doc;
        this.tokens = new TokenIndex(doc.text());
        this.sections = doc.sections();
        this.maxSize = maxSize;
    }

    int sectionAt(int offset) {
        int ans = 0;
        for (ParsedDocument.Section s : sections) {
            if (s.start() <= offset) ans = s.ordinal(); else break;
        }
        return ans;
    }

    List<String> headingPathOf(int sectionOrdinal) {
        return sections.isEmpty() ? List.of() : sections.get(sectionOrdinal).headingPath();
    }

    List<Block> blocksIn(ParsedDocument.Section s) {
        List<Block> out = new ArrayList<>();
        for (Block b : doc.blocks()) if (b.start() >= s.start() && b.end() <= s.end()) out.add(b);
        return out;
    }

    ChunkOut emit(int ordinal, int start, int end, boolean prependHeadingPath) {
        int sec = sectionAt(start);
        String body = doc.text().substring(start, end);
        List<String> path = headingPathOf(sec);
        String text = prependHeadingPath && !path.isEmpty() ? String.join(" > ", path) + "\n" + body : body;
        return new ChunkOut(ordinal, sec, text, tokens.count(start, end), start, end, doc.pageAt(start));
    }

    /** Greedy packing of consecutive ranges up to maxSize tokens; oversize ranges are split further. */
    List<int[]> pack(List<int[]> units, boolean splitOversize, boolean sentenceLevel) {
        List<int[]> out = new ArrayList<>();
        int[] cur = null;
        for (int[] u : units) {
            if (tokens.count(u[0], u[1]) > maxSize) {
                if (cur != null) { out.add(cur); cur = null; }
                if (!splitOversize) out.add(u);
                else if (sentenceLevel) out.addAll(windows(u[0], u[1]));
                else out.addAll(pack(sentences(u[0], u[1]), true, true));
            } else if (cur != null && tokens.count(cur[0], u[1]) <= maxSize) {
                cur = new int[] {cur[0], u[1]};
            } else {
                if (cur != null) out.add(cur);
                cur = u;
            }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    List<int[]> sentences(int start, int end) {
        List<int[]> out = new ArrayList<>();
        Matcher m = SENTENCE_END.matcher(doc.text()).region(start, end);
        int from = start;
        while (m.find()) {
            if (m.start() > from) out.add(new int[] {from, m.start()});
            from = m.end();
        }
        if (end > from) out.add(new int[] {from, end});
        return out;
    }

    /** Last resort: consecutive windows of maxSize tokens. */
    List<int[]> windows(int start, int end) {
        List<int[]> out = new ArrayList<>();
        int first = tokens.firstAtOrAfter(start);
        int last = tokens.firstAtOrAfter(end); // exclusive
        for (int i = first; i < last; i += maxSize) {
            int j = Math.min(last, i + maxSize);
            out.add(new int[] {tokens.start(i), tokens.end(j - 1)});
        }
        return out;
    }

    static boolean atomic(Block b) {
        return b.kind() == Kind.LIST_ITEM || b.kind() == Kind.TABLE_ROW;
    }
}
