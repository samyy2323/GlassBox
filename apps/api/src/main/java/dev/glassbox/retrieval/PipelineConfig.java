package dev.glassbox.retrieval;

import dev.glassbox.rerank.Reranker;

/** Retrieval half of a pipeline: how to search, rerank and pack. (Query transformers and the LLM join in later phases.) */
public record PipelineConfig(RetrievalParams retrieval, Reranker.Type reranker, int rerankCandidates, int topN, int tokenBudget) {

    public static PipelineConfig defaults(String model) {
        return new PipelineConfig(RetrievalParams.hybrid(model), Reranker.Type.NONE, 50, 5, 3000);
    }

    public PipelineConfig withReranker(Reranker.Type type) {
        return new PipelineConfig(retrieval, type, rerankCandidates, topN, tokenBudget);
    }

    public PipelineConfig withTopN(int n) {
        return new PipelineConfig(retrieval, reranker, rerankCandidates, n, tokenBudget);
    }

    public PipelineConfig withTokenBudget(int budget) {
        return new PipelineConfig(retrieval, reranker, rerankCandidates, topN, budget);
    }

    public PipelineConfig withMode(RetrievalParams.Mode mode) {
        return new PipelineConfig(retrieval.withMode(mode), reranker, rerankCandidates, topN, tokenBudget);
    }
}
