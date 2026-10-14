package dev.glassbox.rerank;

import dev.glassbox.embed.SidecarClient;
import dev.glassbox.retrieval.Candidate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Scores each (query, chunk) pair with the sidecar's cross-encoder. */
@Component
@ConditionalOnProperty(name = "glassbox.embed.sidecar.enabled", havingValue = "true", matchIfMissing = true)
public class SidecarCrossEncoderReranker implements Reranker {
    static final String MODEL = "ms-marco-MiniLM-L-6-v2";
    /** The sidecar accepts at most 256 documents per call. */
    static final int BATCH = 128;

    private final SidecarClient client;

    public SidecarCrossEncoderReranker(SidecarClient client) {
        this.client = client;
    }

    @Override public Type type() { return Type.CROSS_ENCODER; }

    @Override
    public List<Candidate> rerank(String query, List<Candidate> candidates, int topN) {
        if (candidates.isEmpty()) return List.of();
        List<Double> scores = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i += BATCH) {
            List<String> docs = candidates.subList(i, Math.min(candidates.size(), i + BATCH)).stream().map(Candidate::text).toList();
            var r = client.rerank(MODEL, query, docs);
            if (r == null || r.scores() == null || r.scores().size() != docs.size()) {
                throw new IllegalStateException("sidecar returned a wrong number of rerank scores");
            }
            scores.addAll(r.scores());
        }
        List<Candidate> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) scored.add(candidates.get(i).withRerankScore(scores.get(i)));
        // Stable on ties: equal scores keep their fused order.
        scored.sort(Comparator.comparingDouble((Candidate c) -> c.rerankScore()).reversed());
        return scored.subList(0, Math.min(topN, scored.size()));
    }
}
