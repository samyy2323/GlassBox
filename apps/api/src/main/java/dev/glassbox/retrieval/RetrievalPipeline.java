package dev.glassbox.retrieval;

import dev.glassbox.embed.EmbeddingProviders;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.rerank.Rerankers;
import dev.glassbox.retrieval.ContextBuilder.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Query -> embed -> hybrid search -> rerank -> context (SPEC 6 stages 2 to 4). Authorization is the caller's job;
 * tenancy is enforced by RLS underneath. Each stage writes a {@link Span}.
 */
@Service
public class RetrievalPipeline {
    public record Result(String query, UUID chunkingId, PipelineConfig config, List<Candidate> candidates,
                         List<Candidate> ranked, Context context, List<Span> spans) {}

    private final ChunkingResolver chunkings;
    private final EmbeddingProviders embeddings;
    private final HybridRetriever retriever;
    private final Rerankers rerankers;
    private final ContextBuilder contextBuilder;

    public RetrievalPipeline(ChunkingResolver chunkings, EmbeddingProviders embeddings, HybridRetriever retriever,
                             Rerankers rerankers, ContextBuilder contextBuilder) {
        this.chunkings = chunkings;
        this.embeddings = embeddings;
        this.retriever = retriever;
        this.rerankers = rerankers;
        this.contextBuilder = contextBuilder;
    }

    public Result retrieve(UUID workspaceId, String query, PipelineConfig config) {
        List<Span> spans = new ArrayList<>();
        RetrievalParams params = config.retrieval();

        UUID chunkingId = chunkings.defaultChunkingId(workspaceId).orElse(null);
        if (chunkingId == null) { // nothing ingested yet
            Context empty = contextBuilder.build(List.of(), config.tokenBudget());
            return new Result(query, null, config, List.of(), List.of(), empty, spans);
        }

        // Keyword-only search needs no embedding; the SQL still wants a vector of the right size (its branch is LIMIT 0).
        long t = System.nanoTime();
        float[] qvec = params.mode() == RetrievalParams.Mode.KEYWORD
                ? new float[HybridSql.MODEL_DIMS.get(params.model())]
                : embeddings.get(params.model()).embed(List.of(query)).get(0);
        spans.add(span("query_embedding", t, Map.of("model", params.model(), "skipped", params.mode() == RetrievalParams.Mode.KEYWORD)));

        t = System.nanoTime();
        List<Candidate> candidates = retriever.search(workspaceId, chunkingId, query, qvec, params);
        spans.add(span("retrieval", t, Map.of("mode", params.mode(), "candidates", candidates.size(), "kFused", params.kFused(),
                "rrfK", params.rrfK(), "wVec", params.wVec(), "wKw", params.wKw())));

        t = System.nanoTime();
        Reranker reranker = rerankers.get(config.reranker());
        List<Candidate> pool = candidates.subList(0, Math.min(config.rerankCandidates(), candidates.size()));
        List<Candidate> ranked = reranker.rerank(query, pool, config.topN());
        spans.add(span("rerank", t, Map.of("reranker", config.reranker(), "in", pool.size(), "out", ranked.size())));

        t = System.nanoTime();
        Context context = contextBuilder.build(ranked, config.tokenBudget());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("included", context.included().size());
        detail.put("dropped", context.dropped().size());
        detail.put("tokensUsed", context.tokensUsed());
        detail.put("budget", context.budget());
        spans.add(span("context", t, detail));

        return new Result(query, chunkingId, config, candidates, ranked, context, spans);
    }

    private static Span span(String stage, long startNanos, Map<String, Object> detail) {
        return new Span(stage, (System.nanoTime() - startNanos) / 1_000_000, detail);
    }
}
