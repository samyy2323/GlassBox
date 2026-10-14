package dev.glassbox.ingest;

import dev.glassbox.chunking.Chunker;
import dev.glassbox.chunking.RecursiveChunker;

/** The chunking every upload gets, shared with retrieval so both agree on which chunking row to use. */
public final class IngestDefaults {
    private IngestDefaults() {}

    public static Chunker chunker(int chunkSize) {
        return new RecursiveChunker(chunkSize, true);
    }
}
