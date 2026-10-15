package dev.glassbox.retrieval;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.glassbox.rerank.Reranker;

/**
 * Retrieval half of a pipeline: how to search, rerank and pack. (Query transformers and the LLM join in later phases.)
 * {@code chunking} is null for "the workspace's default chunking" and is then left out of the JSON, so the hash of a config
 * that never named a chunking is the same as before chunkings became part of the config.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineConfig(RetrievalParams retrieval, Reranker.Type reranker, int rerankCandidates, int topN, int tokenBudget,
                             Chunking chunking) {

    public PipelineConfig(RetrievalParams retrieval, Reranker.Type reranker, int rerankCandidates, int topN, int tokenBudget) {
        this(retrieval, reranker, rerankCandidates, topN, tokenBudget, null);
    }

    public static PipelineConfig defaults(String model) {
        return new PipelineConfig(RetrievalParams.hybrid(model), Reranker.Type.NONE, 50, 5, 3000);
    }

    public PipelineConfig withReranker(Reranker.Type type) {
        return new PipelineConfig(retrieval, type, rerankCandidates, topN, tokenBudget, chunking);
    }

    public PipelineConfig withTopN(int n) {
        return new PipelineConfig(retrieval, reranker, rerankCandidates, n, tokenBudget, chunking);
    }

    public PipelineConfig withTokenBudget(int budget) {
        return new PipelineConfig(retrieval, reranker, rerankCandidates, topN, budget, chunking);
    }

    public PipelineConfig withMode(RetrievalParams.Mode mode) {
        return new PipelineConfig(retrieval.withMode(mode), reranker, rerankCandidates, topN, tokenBudget, chunking);
    }

    public PipelineConfig withChunking(Chunking c) {
        return new PipelineConfig(retrieval, reranker, rerankCandidates, topN, tokenBudget, c);
    }

    public PipelineConfig withModel(String model) {
        return new PipelineConfig(retrieval.withModel(model), reranker, rerankCandidates, topN, tokenBudget, chunking);
    }
}
