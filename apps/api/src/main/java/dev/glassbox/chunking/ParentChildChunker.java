package dev.glassbox.chunking;

import dev.glassbox.parse.ParsedDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small chunks for matching, big chunks for reading. The document is first cut into PARENT chunks the way the recursive chunker
 * does; each parent is then cut into CHILD chunks of at most {@code childSize} tokens. Children are embedded and searched; when one
 * is retrieved the prompt gets its parent. A parent is emitted directly before its children, so a parent always has the lower ordinal.
 */
public class ParentChildChunker implements Chunker {
    private final int parentSize;
    private final int childSize;

    public ParentChildChunker(int parentSize, int childSize) {
        if (childSize < 1 || childSize > parentSize) throw new IllegalArgumentException("need 1 <= childSize <= parentSize");
        this.parentSize = parentSize;
        this.childSize = childSize;
    }

    @Override public String strategy() { return "parent_child"; }

    @Override public Map<String, Object> params() { return Map.of("parentSize", parentSize, "childSize", childSize); }

    @Override
    public List<ChunkOut> chunk(ParsedDocument doc) {
        List<ChunkOut> parents = new RecursiveChunker(parentSize, true).chunk(doc);
        ChunkSupport cs = new ChunkSupport(doc, childSize);
        List<ChunkOut> out = new ArrayList<>();
        for (ChunkOut p : parents) {
            int parentOrdinal = out.size();
            out.add(new ChunkOut(parentOrdinal, p.sectionOrdinal(), p.text(), p.tokenCount(), p.start(), p.end(), p.page(), Role.PARENT, null));
            for (int[] r : cs.pack(cs.sentences(p.start(), p.end()), true, true)) {
                ChunkOut c = cs.emit(out.size(), r[0], r[1], true);
                out.add(new ChunkOut(c.ordinal(), c.sectionOrdinal(), c.text(), c.tokenCount(), c.start(), c.end(), c.page(), Role.CHILD, parentOrdinal));
            }
        }
        return out;
    }
}
