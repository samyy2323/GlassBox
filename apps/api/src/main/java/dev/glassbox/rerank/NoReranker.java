package dev.glassbox.rerank;

import dev.glassbox.retrieval.Candidate;
import java.util.List;
import org.springframework.stereotype.Component;

/** Keeps the fused order and just truncates. */
@Component
public class NoReranker implements Reranker {
    @Override public Type type() { return Type.NONE; }

    @Override
    public List<Candidate> rerank(String query, List<Candidate> candidates, int topN) {
        return candidates.subList(0, Math.min(topN, candidates.size()));
    }
}
