package dev.glassbox.embed;

import java.util.List;

/** Turns text into vectors. Every implementation is a swappable strategy (SPEC 3). */
public interface EmbeddingProvider {
    /** Stable id stored in {@code embeddings.model}; it must have an HNSW index in the schema. */
    String modelId();

    int dimension();

    /** Embeds all texts, returning vectors in the same order. Implementations batch, retry and rate-limit. */
    List<float[]> embed(List<String> texts);
}
