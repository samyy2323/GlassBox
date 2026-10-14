package dev.glassbox.rerank;

import dev.glassbox.retrieval.Candidate;
import java.util.List;

/** Re-sorts retrieval candidates by a stronger relevance model (SPEC 6). */
public interface Reranker {
    enum Type { NONE, CROSS_ENCODER }

    Type type();

    /** Returns at most topN candidates, best first. Cross-encoders set {@link Candidate#rerankScore()}. */
    List<Candidate> rerank(String query, List<Candidate> candidates, int topN);
}
