package dev.glassbox.cost;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The projected cost of a run (SPEC 11, T2.8): questions times configurations times stages times tokens times the price, discounted by the
 * share of calls the cache is expected to serve. Pure, so every line of the breakdown is a testable sum.
 *
 * A language model is priced per million prompt and completion tokens; embeddings have their own price (zero for the local sidecar). A local
 * model costs $0, so a breakdown always carries the tokens and calls too: those are what a budget on a local setup is spent in.
 */
public final class CostEstimator {
    public record Price(double promptUsdPerMillion, double completionUsdPerMillion) {
        public static final Price FREE = new Price(0, 0);

        double usd(long promptTokens, long completionTokens) {
            return (promptTokens * promptUsdPerMillion + completionTokens * completionUsdPerMillion) / 1_000_000.0;
        }
    }

    /** What a run will consume, by the four things SPEC names, plus the model calls made before the search (query transformers). */
    public record Usage(long embeddedTokens,
                        long generationPrompt, long generationCompletion, int generationCalls,
                        long judgingPrompt, long judgingCompletion, int judgingCalls,
                        long rewritingPrompt, long rewritingCompletion, int rewritingCalls,
                        long synthesisPrompt, long synthesisCompletion, int synthesisCalls) {
        public static Usage none() {
            return new Usage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record Line(long tokens, int calls, double usd) {}

    public record Breakdown(Line embeddings, Line generation, Line judging, Line rewriting, Line synthesis, double usd, long tokens, int calls, double cacheHitRate) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("embeddings", embeddings);
            m.put("generation", generation);
            m.put("judging", judging);
            m.put("rewriting", rewriting);
            m.put("synthesis", synthesis);
            m.put("usd", usd);
            m.put("tokens", tokens);
            m.put("calls", calls);
            m.put("cacheHitRate", cacheHitRate);
            return m;
        }
    }

    private CostEstimator() {}

    /**
     * @param cacheHitRate share of cacheable calls expected to be served from the cache, 0 to 1. Judging, rewriting and question synthesis run at
     *                     temperature 0 and are cacheable; the answer itself is not discounted.
     */
    public static Breakdown price(Usage u, Price llm, double embeddingUsdPerMillion, double cacheHitRate) {
        double hit = Math.max(0, Math.min(1, cacheHitRate)), miss = 1 - hit;
        Line embeddings = new Line(u.embeddedTokens(), 0, u.embeddedTokens() * embeddingUsdPerMillion / 1_000_000.0);
        Line generation = line(u.generationPrompt(), u.generationCompletion(), u.generationCalls(), 1, llm);
        Line judging = line(u.judgingPrompt(), u.judgingCompletion(), u.judgingCalls(), miss, llm);
        Line rewriting = line(u.rewritingPrompt(), u.rewritingCompletion(), u.rewritingCalls(), miss, llm);
        Line synthesis = line(u.synthesisPrompt(), u.synthesisCompletion(), u.synthesisCalls(), miss, llm);
        double usd = embeddings.usd() + generation.usd() + judging.usd() + rewriting.usd() + synthesis.usd();
        long tokens = embeddings.tokens() + generation.tokens() + judging.tokens() + rewriting.tokens() + synthesis.tokens();
        int calls = generation.calls() + judging.calls() + rewriting.calls() + synthesis.calls();
        return new Breakdown(embeddings, generation, judging, rewriting, synthesis, usd, tokens, calls, hit);
    }

    private static Line line(long prompt, long completion, int calls, double share, Price llm) {
        long p = Math.round(prompt * share), c = Math.round(completion * share);
        return new Line(p + c, (int) Math.round(calls * share), llm.usd(p, c));
    }

    /** SPEC: a run aborts when its actual spend passes 120% of its cap. */
    public static final double ABORT_FACTOR = 1.2;

    public static boolean overCap(double actual, Double cap) {
        return cap != null && actual > cap * ABORT_FACTOR;
    }

    public static boolean overCap(long actual, Integer cap) {
        return cap != null && actual > cap * ABORT_FACTOR;
    }
}
