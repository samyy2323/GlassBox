package dev.glassbox.chunking;

import dev.glassbox.parse.ParsedDocument;
import dev.glassbox.parse.ParsedDocument.Block;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One chunk per section; oversized sections are split between blocks. A list item or table row is never
 * split, even when it alone exceeds maxSize. Plain paragraphs that are too big fall back to sentence and
 * word windows. Every chunk is prefixed with its heading path.
 */
public class StructureChunker implements Chunker {
    private final int maxSize;

    public StructureChunker(int maxSize) {
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be positive");
        this.maxSize = maxSize;
    }

    @Override public String strategy() { return "structure"; }

    @Override public Map<String, Object> params() { return Map.of("maxSize", maxSize); }

    @Override
    public List<ChunkOut> chunk(ParsedDocument doc) {
        ChunkSupport cs = new ChunkSupport(doc, maxSize);
        List<ChunkOut> out = new ArrayList<>();
        for (ParsedDocument.Section s : cs.sections) {
            List<int[]> units = new ArrayList<>();
            List<int[]> keepWhole = new ArrayList<>();
            for (Block b : cs.blocksIn(s)) {
                int[] r = {b.start(), b.end()};
                units.add(r);
                if (ChunkSupport.atomic(b)) keepWhole.add(r);
            }
            for (int[] r : packRespectingAtoms(cs, units, keepWhole)) out.add(cs.emit(out.size(), r[0], r[1], true));
        }
        return out;
    }

    /** Like ChunkSupport.pack, but ranges listed in keepWhole are emitted intact when oversize. */
    private List<int[]> packRespectingAtoms(ChunkSupport cs, List<int[]> units, List<int[]> keepWhole) {
        List<int[]> out = new ArrayList<>();
        List<int[]> run = new ArrayList<>();
        for (int[] u : units) {
            boolean oversizeAtom = keepWhole.contains(u) && cs.tokens.count(u[0], u[1]) > maxSize;
            if (oversizeAtom) {
                out.addAll(cs.pack(run, true, false));
                run.clear();
                out.add(u);
            } else {
                run.add(u);
            }
        }
        out.addAll(cs.pack(run, true, false));
        return out;
    }
}
