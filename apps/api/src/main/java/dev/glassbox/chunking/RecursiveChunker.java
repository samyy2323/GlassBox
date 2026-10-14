package dev.glassbox.chunking;

import dev.glassbox.parse.ParsedDocument;
import dev.glassbox.parse.ParsedDocument.Block;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits on paragraph, then sentence, then word windows until each piece fits, and merges neighbours
 * back up to maxSize. Never crosses a section boundary, so a chunk has exactly one heading path.
 */
public class RecursiveChunker implements Chunker {
    private final int maxSize;
    private final boolean prependHeadingPath;

    public RecursiveChunker(int maxSize, boolean prependHeadingPath) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be positive");
        this.maxSize = maxSize;
        this.prependHeadingPath = prependHeadingPath;
    }

    @Override public String strategy() { return "recursive"; }

    @Override public Map<String, Object> params() { return Map.of("maxSize", maxSize, "prependHeadingPath", prependHeadingPath); }

    @Override
    public List<ChunkOut> chunk(ParsedDocument doc) {
        ChunkSupport cs = new ChunkSupport(doc, maxSize);
        List<ChunkOut> out = new ArrayList<>();
        for (ParsedDocument.Section s : cs.sections) {
            List<int[]> units = new ArrayList<>();
            for (Block b : cs.blocksIn(s)) units.add(new int[] {b.start(), b.end()});
            for (int[] r : cs.pack(units, true, false)) out.add(cs.emit(out.size(), r[0], r[1], prependHeadingPath));
        }
        return out;
    }
}
