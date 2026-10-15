package dev.glassbox.autotune;

import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.Chunking;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalParams;
import dev.glassbox.retrieval.transform.Transformer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The configurations AutoTune may try (SPEC 9), searched in two stages because the full grid has thousands of points:
 * stage 1 varies the chunking and the embedding model with everything else at the baseline, stage 2 varies the retrieval settings around the
 * stage 1 winner. Both stages draw a fixed-size deterministic sample of their grid (the baseline is always in it), so a search costs what the
 * user budgeted, not what the grid would.
 *
 * Query transformers and the generation model are not part of the space yet (they arrive in P5 and as an option later); every candidate
 * records its settings by dimension so the report can show what each one did.
 */
public final class SearchSpace {
    public static final List<Integer> TOP_N = List.of(3, 5, 10);
    public static final List<Integer> K_FUSED = List.of(20, 50);
    public static final List<Integer> BUDGETS = List.of(2000, 3000, 6000);
    /** Hybrid weight pairs (vector : keyword). */
    public static final List<double[]> WEIGHTS = List.of(new double[] {1, 1}, new double[] {2, 1}, new double[] {1, 2});

    /** {@code key} identifies the configuration inside one AutoTune; {@code settings} are its values per dimension. */
    public record Candidate(String key, String label, PipelineConfig config, Map<String, String> settings) {}

    private SearchSpace() {}

    /** Fixed 512 tokens, the small embedding model, vector search only, top 5 and no reranker: what a first prototype would use. */
    public static PipelineConfig baseline(String model) {
        return config(new Chunking("fixed", 512, 0), model, RetrievalParams.Mode.VECTOR, 1, 1, 50, Reranker.Type.NONE, 5, 3000);
    }

    static PipelineConfig config(Chunking chunking, String model, RetrievalParams.Mode mode, double wVec, double wKw, int kFused, Reranker.Type reranker, int topN, int budget) {
        return config(chunking, model, mode, wVec, wKw, kFused, reranker, topN, budget, Transformer.NONE);
    }

    static PipelineConfig config(Chunking chunking, String model, RetrievalParams.Mode mode, double wVec, double wKw, int kFused, Reranker.Type reranker, int topN, int budget,
                                 Transformer transformer) {
        int pool = Math.max(50, kFused);
        RetrievalParams params = new RetrievalParams(mode, model, pool, pool, 60, wVec, wKw, kFused, 100, RetrievalParams.KeywordMatch.ANY);
        return new PipelineConfig(params, reranker, pool, topN, budget, chunking, transformer);
    }

    static Map<String, String> settings(PipelineConfig c) {
        Map<String, String> s = new LinkedHashMap<>();
        Chunking ch = c.chunking() == null ? new Chunking("recursive", 512, 0) : c.chunking();
        s.put("chunking", ch.strategy());
        s.put("chunk size", String.valueOf(ch.size()));
        s.put("overlap", ch.overlapPct() + "%");
        s.put("embedding model", c.retrieval().model());
        RetrievalParams r = c.retrieval();
        s.put("retriever", r.mode() == RetrievalParams.Mode.HYBRID ? "hybrid " + trim(r.wVec()) + ":" + trim(r.wKw()) : r.mode().name().toLowerCase());
        s.put("candidates", String.valueOf(r.kFused()));
        s.put("reranker", c.reranker() == Reranker.Type.NONE ? "none" : "cross-encoder");
        s.put("top n", String.valueOf(c.topN()));
        s.put("context budget", String.valueOf(c.tokenBudget()));
        s.put("query transform", c.transformer() == null ? "none" : c.transformer().name().toLowerCase().replace('_', '-'));
        return s;
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    static Candidate candidate(PipelineConfig c) {
        Map<String, String> s = settings(c);
        String key = String.join("|", s.values());
        return new Candidate(key, label(s), c, s);
    }

    static String label(Map<String, String> s) {
        return s.get("chunking") + " " + s.get("chunk size") + (s.get("overlap").equals("0%") ? "" : " +" + s.get("overlap")) + " · " + s.get("embedding model") + " · "
                + s.get("retriever") + " · top " + s.get("top n") + (s.get("reranker").equals("none") ? "" : " · rerank")
                + (s.get("query transform").equals("none") ? "" : " · " + s.get("query transform"));
    }

    /** Every chunking AutoTune knows how to build. */
    public static List<Chunking> chunkings() {
        List<Chunking> out = new ArrayList<>();
        for (int size : Chunking.SIZES) {
            for (int overlap : Chunking.OVERLAPS) out.add(new Chunking("fixed", size, overlap));
            out.add(new Chunking("recursive", size, 0));
            out.add(new Chunking("structure", size, 0));
            out.add(new Chunking("parent_child", size, 0));
        }
        return out;
    }

    /** Stage 1: chunking x embedding model at the baseline's retrieval settings. At most {@code max} candidates, the baseline always one of them. */
    public static List<Candidate> stage1(long seed, int max, List<String> models, String baselineModel) {
        PipelineConfig base = baseline(baselineModel);
        List<Candidate> grid = new ArrayList<>();
        for (Chunking ch : chunkings()) {
            for (String model : models) {
                grid.add(candidate(config(ch, model, base.retrieval().mode(), 1, 1, base.retrieval().kFused(), base.reranker(), base.topN(), base.tokenBudget())));
            }
        }
        return sample(grid, candidate(base), seed, max);
    }

    /** Stage 2: retrieval settings around the winner of stage 1 (its chunking and model stay fixed). */
    public static List<Candidate> stage2(long seed, int max, PipelineConfig winner) {
        return stage2(seed, max, winner, false);
    }

    /**
     * @param withTransformers also vary the query transformer. Off by default: a transformer calls the model for every question, which a retrieval-only
     *                         search otherwise never does, so the user turns it on knowingly (and the model calls count against the spend cap).
     */
    public static List<Candidate> stage2(long seed, int max, PipelineConfig winner, boolean withTransformers) {
        List<Candidate> grid = new ArrayList<>();
        List<Transformer> transformers = withTransformers ? List.of(Transformer.values()) : List.of(Transformer.NONE);
        for (RetrievalParams.Mode mode : List.of(RetrievalParams.Mode.VECTOR, RetrievalParams.Mode.HYBRID)) {
            for (double[] w : mode == RetrievalParams.Mode.HYBRID ? WEIGHTS : List.<double[]>of(new double[] {1, 1})) {
                for (int k : K_FUSED) for (Reranker.Type rr : List.of(Reranker.Type.NONE, Reranker.Type.CROSS_ENCODER)) {
                    for (int n : TOP_N) for (int budget : BUDGETS) for (Transformer tr : transformers) {
                        grid.add(candidate(config(winner.chunking(), winner.retrieval().model(), mode, w[0], w[1], k, rr, n, budget, tr)));
                    }
                }
            }
        }
        // the stage 1 winner itself, unchanged, is a candidate: stage 2 can only improve on it
        return sample(grid, candidate(config(winner.chunking(), winner.retrieval().model(), winner.retrieval().mode(), winner.retrieval().wVec(), winner.retrieval().wKw(),
                winner.retrieval().kFused(), winner.reranker(), winner.topN(), winner.tokenBudget(), winner.transformer())), seed, max);
    }

    /** A deterministic sample of at most {@code max} grid points that always contains {@code must}. */
    static List<Candidate> sample(List<Candidate> grid, Candidate must, long seed, int max) {
        List<Candidate> pool = new ArrayList<>(grid.stream().filter(c -> !c.key().equals(must.key())).sorted(Comparator.comparing(Candidate::key)).toList());
        java.util.Collections.shuffle(pool, new Random(seed));
        List<Candidate> out = new ArrayList<>();
        out.add(must);
        for (Candidate c : pool) {
            if (out.size() >= Math.max(1, max)) break;
            out.add(c);
        }
        return out;
    }
}
