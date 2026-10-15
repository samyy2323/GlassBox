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
    /**
     * {@code candidates} is the fused list; {@code reranked} is the reranker's full new order of the pool it saw (so X-Ray can
     * show scores for chunks that were cut); {@code ranked} is its first topN, which is what the context is built from.
     */
    public record Result(String query, UUID chunkingId, PipelineConfig config, List<Candidate> candidates,
                         List<Candidate> reranked, List<Candidate> ranked, Context context, List<Span> spans) {}

    private final ChunkingResolver chunkings;
    private final EmbeddingProviders embeddings;
    private final HybridRetriever retriever;
    private final Rerankers rerankers;
    private final ContextBuilder contextBuilder;
    private final ParentExpander parents;

    public RetrievalPipeline(ChunkingResolver chunkings, EmbeddingProviders embeddings, HybridRetriever retriever,
                             Rerankers rerankers, ContextBuilder contextBuilder, ParentExpander parents) {
        this.parents = parents;
        this.chunkings = chunkings;
        this.embeddings = embeddings;
        this.retriever = retriever;
        this.rerankers = rerankers;
        this.contextBuilder = contextBuilder;
    }

    public Result retrieve(UUID workspaceId, String query, PipelineConfig config) {
        return retrieve(workspaceId, query, config, span -> { });
    }

    /** {@code onSpan} is called as each stage finishes, so a caller can stream the trace while the pipeline runs. */
    public Result retrieve(UUID workspaceId, String query, PipelineConfig config, java.util.function.Consumer<Span> onSpan) {
        List<Span> spans = new ArrayList<>() {
            @Override
            public boolean add(Span s) {
                onSpan.accept(s);
                return super.add(s);
            }
        };
        RetrievalParams params = config.retrieval();

        UUID chunkingId = chunkings.resolve(workspaceId, config.chunking()).orElse(null);
        if (chunkingId == null) { // nothing ingested yet
            Context empty = contextBuilder.build(List.of(), config.tokenBudget());
            return new Result(query, null, config, List.of(), List.of(), List.of(), empty, spans);
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
        List<Candidate> reranked = reranker.rerank(query, pool, pool.size());
        List<Candidate> ranked = reranked.subList(0, Math.min(config.topN(), reranked.size()));
        spans.add(span("rerank", t, Map.of("reranker", config.reranker(), "in", pool.size(), "out", ranked.size())));

        if (config.chunking() != null && config.chunking().parentChild()) { // the prompt gets the parent of each matched child
            t = System.nanoTime();
            ranked = parents.expand(workspaceId, ranked);
            spans.add(span("parent_expansion", t, Map.of("out", ranked.size())));
        }

        t = System.nanoTime();
        Context context = contextBuilder.build(ranked, config.tokenBudget());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("included", context.included().size());
        detail.put("dropped", context.dropped().size());
        detail.put("tokensUsed", context.tokensUsed());
        detail.put("budget", context.budget());
        spans.add(span("context", t, detail));

        return new Result(query, chunkingId, config, candidates, reranked, ranked, context, spans);
    }

    private static Span span(String stage, long startNanos, Map<String, Object> detail) {
        return new Span(stage, (System.nanoTime() - startNanos) / 1_000_000, detail);
    }
}
