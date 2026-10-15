package dev.glassbox.retrieval;

import dev.glassbox.chunking.Chunker;
import java.util.List;
import java.util.Map;

/**
 * Which chunking a pipeline searches: the strategy, its size in tokens and the overlap as a share of the size. For parent_child the size
 * is the parent's and the child is a quarter of it. It is a request, turned into the {@link Chunker} whose strategy and params identify
 * the stored chunking row.
 */
public record Chunking(String strategy, int size, int overlapPct) {
    public static final List<String> STRATEGIES = List.of("fixed", "recursive", "structure", "parent_child");
    public static final List<Integer> SIZES = List.of(256, 512, 1024);
    public static final List<Integer> OVERLAPS = List.of(0, 10, 20);

    public Chunking {
        if (!STRATEGIES.contains(strategy)) throw new IllegalArgumentException("Unknown chunking strategy: " + strategy);
        if (size < 16 || size > 8192) throw new IllegalArgumentException("Chunk size must be 16 to 8192 tokens");
        if (overlapPct < 0 || overlapPct > 50) throw new IllegalArgumentException("Overlap must be 0 to 50 percent");
        if (!strategy.equals("fixed")) overlapPct = 0; // only fixed windows overlap; keeps equal chunkings equal configs
    }

    public Chunker chunker() {
        return switch (strategy) {
            case "fixed" -> Chunker.create("fixed", Map.of("size", size, "overlap", size * overlapPct / 100));
            case "recursive" -> Chunker.create("recursive", Map.of("maxSize", size, "prependHeadingPath", true));
            case "structure" -> Chunker.create("structure", Map.of("maxSize", size));
            default -> Chunker.create("parent_child", Map.of("parentSize", size, "childSize", Math.max(16, size / 4)));
        };
    }

    public boolean parentChild() {
        return strategy.equals("parent_child");
    }

    public String label() {
        return strategy + " " + size + (overlapPct > 0 ? " +" + overlapPct + "%" : "");
    }
}
