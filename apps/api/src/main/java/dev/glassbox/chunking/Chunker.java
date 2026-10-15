package dev.glassbox.chunking;

import dev.glassbox.parse.ParsedDocument;
import java.util.List;
import java.util.Map;

public interface Chunker {
    /** Stored in {@code chunkings.strategy}. */
    String strategy();

    /** Stored in {@code chunkings.params}; together with the strategy it identifies one chunking config. */
    Map<String, Object> params();

    List<ChunkOut> chunk(ParsedDocument doc);

    /**
     * {@code text} is what gets embedded and keyword-indexed (it may carry a heading-path prefix);
     * {@code start}/{@code end} always address the original document text.
     */
    record ChunkOut(int ordinal, int sectionOrdinal, String text, int tokenCount, int start, int end, Integer page,
                    Role role, Integer parentOrdinal) {
        public ChunkOut(int ordinal, int sectionOrdinal, String text, int tokenCount, int start, int end, Integer page) {
            this(ordinal, sectionOrdinal, text, tokenCount, start, end, page, Role.FLAT, null);
        }
    }

    /** PARENT chunks are stored for the prompt only; CHILD chunks are what gets embedded and searched. */
    enum Role { FLAT, PARENT, CHILD }

    /** Builds a chunker from a stored/requested config. */
    static Chunker create(String strategy, Map<String, Object> params) {
        return switch (strategy) {
            case "parent_child" -> new ParentChildChunker(intParam(params, "parentSize", 1024), intParam(params, "childSize", 256));
            case "fixed" -> new FixedChunker(intParam(params, "size", 512), intParam(params, "overlap", 51));
            case "recursive" -> new RecursiveChunker(intParam(params, "maxSize", 512),
                    Boolean.parseBoolean(String.valueOf(params.getOrDefault("prependHeadingPath", true))));
            case "structure" -> new StructureChunker(intParam(params, "maxSize", 512));
            default -> throw new IllegalArgumentException("Unknown chunking strategy: " + strategy);
        };
    }

    private static int intParam(Map<String, Object> p, String key, int def) {
        Object v = p.get(key);
        return v == null ? def : ((Number) v).intValue();
    }
}
