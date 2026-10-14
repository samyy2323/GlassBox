package dev.glassbox.chunking;

import dev.glassbox.parse.ParsedDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Fixed token windows with overlap. Ignores document structure. */
public class FixedChunker implements Chunker {
    private final int size;
    private final int overlap;

    public FixedChunker(int size, int overlap) {
        if (size < 1 || overlap < 0 || overlap >= size) throw new IllegalArgumentException("need 0 <= overlap < size");
        this.size = size;
        this.overlap = overlap;
    }

    @Override public String strategy() { return "fixed"; }

    @Override public Map<String, Object> params() { return Map.of("size", size, "overlap", overlap); }

    @Override
    public List<ChunkOut> chunk(ParsedDocument doc) {
        ChunkSupport cs = new ChunkSupport(doc, size);
        List<ChunkOut> out = new ArrayList<>();
        int n = cs.tokens.size();
        int step = size - overlap;
        for (int s = 0; s < n; s += step) {
            int e = Math.min(n, s + size);
            out.add(cs.emit(out.size(), cs.tokens.start(s), cs.tokens.end(e - 1), false));
            if (e == n) break;
        }
        return out;
    }
}
