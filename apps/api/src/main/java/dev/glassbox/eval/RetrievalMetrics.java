package dev.glassbox.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Retrieval metrics of SPEC 7, computed per question from character ranges (gold evidence is never a chunk id).
 * A retrieved chunk hits a gold span when it overlaps at least half of that span; a question with several gold spans
 * (multi-hop, comparison) is fully recalled only when every span is hit.
 */
public final class RetrievalMetrics {
    public static final double HIT_THRESHOLD = 0.5;

    public static final String RECALL_5 = "recall@5";
    public static final String RECALL_10 = "recall@10";
    public static final String MRR = "mrr";
    public static final String NDCG_10 = "ndcg@10";
    public static final String CONTEXT_RECALL = "context_recall";
    /** Pass/fail versions, used where a binary test (McNemar) is the right one. */
    public static final String PASS_5 = "pass@5";
    public static final String CONTEXT_PASS = "context_pass";

    /** The order metrics are shown in. */
    public static final List<String> ALL = List.of(RECALL_5, RECALL_10, MRR, NDCG_10, CONTEXT_RECALL, PASS_5, CONTEXT_PASS);
    public static final Set<String> BINARY = Set.of(PASS_5, CONTEXT_PASS);

    /** A retrieved chunk: where it sits in its document. */
    public record Ref(UUID documentId, int start, int end) {}

    /** A gold span: the evidence for a question. */
    public record Gold(UUID documentId, int start, int end) {}

    private RetrievalMetrics() {}

    public static boolean hits(Ref chunk, Gold span) {
        if (!chunk.documentId().equals(span.documentId())) return false;
        int length = span.end() - span.start();
        if (length <= 0) return false;
        int overlap = Math.min(chunk.end(), span.end()) - Math.max(chunk.start(), span.start());
        return overlap > 0 && overlap >= HIT_THRESHOLD * length;
    }

    static boolean hitsAny(Ref chunk, List<Gold> gold) {
        for (Gold g : gold) if (hits(chunk, g)) return true;
        return false;
    }

    /** Share of gold spans hit by at least one of the first k chunks. */
    public static double recallAtK(List<Ref> ranked, List<Gold> gold, int k) {
        if (gold.isEmpty()) return Double.NaN;
        List<Ref> top = ranked.subList(0, Math.min(k, ranked.size()));
        int covered = 0;
        for (Gold g : gold) {
            for (Ref c : top) {
                if (hits(c, g)) {
                    covered++;
                    break;
                }
            }
        }
        return (double) covered / gold.size();
    }

    /** 1 / rank of the first chunk that hits any gold span; 0 when none does. */
    public static double reciprocalRank(List<Ref> ranked, List<Gold> gold) {
        for (int i = 0; i < ranked.size(); i++) if (hitsAny(ranked.get(i), gold)) return 1.0 / (i + 1);
        return 0.0;
    }

    /** Binary-relevance nDCG@k. The ideal list holds one relevant chunk per gold span, so a two-span question needs two good chunks to score 1. */
    public static double ndcgAtK(List<Ref> ranked, List<Gold> gold, int k) {
        if (gold.isEmpty()) return Double.NaN;
        double dcg = 0;
        for (int i = 0; i < Math.min(k, ranked.size()); i++) if (hitsAny(ranked.get(i), gold)) dcg += 1.0 / log2(i + 2);
        double ideal = 0;
        for (int i = 0; i < Math.min(k, gold.size()); i++) ideal += 1.0 / log2(i + 2);
        return dcg / ideal;
    }

    /** All metrics of one question. {@code ranked} is the reranker's order of the pool, {@code context} the chunks that actually reached the prompt. */
    public static Map<String, Double> compute(List<Ref> ranked, List<Ref> context, List<Gold> gold) {
        Map<String, Double> m = new LinkedHashMap<>();
        double r5 = recallAtK(ranked, gold, 5), ctx = recallAtK(context, gold, Integer.MAX_VALUE);
        m.put(RECALL_5, r5);
        m.put(RECALL_10, recallAtK(ranked, gold, 10));
        m.put(MRR, reciprocalRank(ranked, gold));
        m.put(NDCG_10, ndcgAtK(ranked, gold, 10));
        m.put(CONTEXT_RECALL, ctx);
        m.put(PASS_5, r5 >= 1.0 ? 1.0 : 0.0);
        m.put(CONTEXT_PASS, ctx >= 1.0 ? 1.0 : 0.0);
        return m;
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2);
    }
}
