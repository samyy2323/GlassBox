package dev.glassbox.diagnose;

import dev.glassbox.diagnose.FailureClassifier.Stage;
import dev.glassbox.diagnose.FailureClassifier.Sub;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalParams;
import dev.glassbox.retrieval.transform.Transformer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the failure counts of a run into advice ("38% of failures are ranking misses, so add a reranker"). The rules are plain on
 * purpose. Each recommendation that can be tried carries {@code change}: the one setting to alter in the run's configuration, in the
 * shape of an experiment configuration, so the UI can create an experiment that applies exactly that change and nothing else.
 */
public final class Recommendations {
    /** A stage must explain at least this share of the failures to be worth advice. */
    public static final double MIN_SHARE = 0.10;

    public record Recommendation(Stage stage, Sub sub, int count, int percent, String text, Map<String, Object> change, String changeLabel) {}

    private Recommendations() {}

    /**
     * @param byStage   failures per stage (only stages with failures)
     * @param falseAbstentions failures that were GENERATION_MISS/FALSE_ABSTENTION, a subset of the GENERATION_MISS count
     * @param unfaithful   GENERATION_MISS/UNFAITHFUL
     */
    public static List<Recommendation> build(Map<Stage, Integer> byStage, Map<Sub, Integer> bySub, PipelineConfig config) {
        int failures = byStage.values().stream().mapToInt(Integer::intValue).sum();
        List<Recommendation> out = new ArrayList<>();
        if (failures == 0) return out;
        for (var e : byStage.entrySet()) {
            int n = e.getValue();
            if (n == 0 || (double) n / failures < MIN_SHARE) continue;
            int pct = (int) Math.round(100.0 * n / failures);
            out.add(advice(e.getKey(), n, pct, bySub, config));
        }
        out.sort(Comparator.comparingInt(Recommendation::count).reversed().thenComparing(r -> r.stage().ordinal()));
        return out;
    }

    private static Recommendation advice(Stage stage, int n, int pct, Map<Sub, Integer> bySub, PipelineConfig c) {
        String share = pct + "% of failures are ";
        switch (stage) {
            case CHUNKING_MISS -> {
                boolean big = c.chunking() != null && c.chunking().size() >= 1024;
                Map<String, Object> change = big ? Map.of("chunking", Map.of("strategy", "parent_child", "size", 1024, "overlapPct", 0))
                        : Map.of("chunking", Map.of("strategy", "recursive", "size", 1024, "overlapPct", 0));
                return new Recommendation(stage, null, n, pct, share + "chunking misses: no single chunk holds the evidence, so "
                        + (big ? "search small pieces but read their parents (parent_child)." : "use larger chunks."), change,
                        big ? "Try parent_child chunking" : "Try recursive chunks of 1024 tokens");
            }
            case RETRIEVAL_MISS -> {
                if (c.retrieval().mode() != RetrievalParams.Mode.HYBRID) {
                    return new Recommendation(stage, null, n, pct, share + "retrieval misses: the evidence was never returned, so combine keyword and vector search.",
                            Map.of("mode", "HYBRID"), "Try hybrid search");
                }
                if (c.retrieval().kFused() < 100) {
                    return new Recommendation(stage, null, n, pct, share + "retrieval misses: the evidence was never returned, so fetch more candidates.",
                            Map.of("kFused", 100), "Fetch 100 candidates");
                }
                return new Recommendation(stage, null, n, pct, share + "retrieval misses: the evidence was never returned, so try a stronger embedding model.",
                        Map.of("embeddingModel", "bge-base-en-v1.5"), "Try bge-base");
            }
            case RANKING_MISS -> {
                if (c.reranker() == Reranker.Type.NONE) {
                    return new Recommendation(stage, null, n, pct, share + "ranking misses, so add a reranker.", Map.of("reranker", "CROSS_ENCODER"), "Add the cross-encoder reranker");
                }
                int topN = Math.min(10, Math.max(c.topN() + 2, c.topN() * 2));
                return new Recommendation(stage, null, n, pct, share + "ranking misses even with a reranker, so put more chunks into the prompt.",
                        Map.of("topN", topN), "Use top " + topN);
            }
            case CONTEXT_MISS -> {
                int budget = Math.min(6000, c.tokenBudget() * 2);
                return new Recommendation(stage, null, n, pct, share + "context misses: the evidence was retrieved but cut by the token budget, so raise the budget.",
                        Map.of("tokenBudget", budget), "Use a budget of " + budget + " tokens");
            }
            case PARTIAL_RETRIEVAL -> {
                if (c.transformer() != Transformer.DECOMPOSE) {
                    return new Recommendation(stage, null, n, pct, share + "partial retrievals: multi-part questions got only some of their evidence, so split the question into sub-questions and search for each.",
                            Map.of("transformer", "DECOMPOSE"), "Decompose the question");
                }
                int topN = Math.min(10, c.topN() + 3);
                return new Recommendation(stage, null, n, pct, share + "partial retrievals even with decomposition, so put more chunks into the prompt.", Map.of("topN", topN), "Use top " + topN);
            }
            case GENERATION_MISS -> {
                int falseAbstain = bySub.getOrDefault(Sub.FALSE_ABSTENTION, 0), unfaithful = bySub.getOrDefault(Sub.UNFAITHFUL, 0);
                String why = falseAbstain >= unfaithful && falseAbstain > 0 ? "the model refused questions its prompt answered, so loosen the abstention wording."
                        : unfaithful > 0 ? "the answers say things the prompt does not, so use a stronger model or a stricter prompt."
                        : "the evidence was in the prompt but the answer is wrong, so use a stronger model.";
                return new Recommendation(stage, falseAbstain >= unfaithful && falseAbstain > 0 ? Sub.FALSE_ABSTENTION : unfaithful > 0 ? Sub.UNFAITHFUL : null,
                        n, pct, share + "generation misses: " + why, null, null);
            }
            case HALLUCINATED_ANSWER -> {
                return new Recommendation(stage, null, n, pct, share + "answers to unanswerable questions, so make the prompt stricter about saying \"not in the documents\".", null, null);
            }
            default -> {
                return new Recommendation(stage, null, n, pct, share + "bad test questions (ambiguous or not answerable from their evidence), so fix the test set, not the pipeline.", null, null);
            }
        }
    }

    /** Failures grouped for the report, every stage present (zero when none) so charts keep a stable order. */
    public static Map<Stage, Integer> zeroed() {
        Map<Stage, Integer> m = new LinkedHashMap<>();
        for (Stage s : Stage.values()) m.put(s, 0);
        return m;
    }
}
