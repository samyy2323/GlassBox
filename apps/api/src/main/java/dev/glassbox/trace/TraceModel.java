package dev.glassbox.trace;

import dev.glassbox.generate.CitationParser.Citation;
import dev.glassbox.generate.LlmProvider;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.Span;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Everything X-Ray shows about one answer. Stored as JSON in {@code traces.data}. */
public final class TraceModel {
    private TraceModel() {}

    public enum Status { OK, ABSTAINED, ERROR, CANCELLED }

    /** What happened to a retrieved chunk on its way to the prompt. */
    public enum Disposition {
        /** Reached the prompt, under {@code label}. */
        IN_CONTEXT,
        /** Ranked high enough but removed while packing the context (budget, duplicate, overlap): see {@code reason}. */
        DROPPED,
        /** Retrieved, but not among the top N after ranking, or outside the reranker's pool. */
        CUT
    }

    /** {@code snippet} is truncated; the full text is fetched from the chunk by id when needed. */
    public record CandidateView(UUID chunkId, UUID documentId, String title, Integer page, int charStart, int charEnd,
                                int tokenCount, String snippet, double fusedScore, Integer vecRank, Double vecDistance,
                                Integer kwRank, Double kwScore, Double rerankScore, Integer rankedPosition,
                                Disposition disposition, String label, String reason) {}

    public record IncludedView(String label, UUID chunkId, int tokenCount) {}

    public record DroppedView(UUID chunkId, String reason, String detail) {}

    public record ContextView(int budget, int tokensUsed, List<IncludedView> included, List<DroppedView> dropped) {}

    public record Usage(int promptTokens, int answerTokens) {}

    public record TraceData(PipelineConfig config, List<CandidateView> candidates, ContextView context,
                            List<LlmProvider.Message> prompt, List<Citation> citations, List<String> unknownCitations,
                            Usage usage, String error) {}

    /** The row as returned by GET /api/traces/{id}. */
    public record Trace(UUID id, String kind, Status status, String question, String answer, String model, Integer latencyMs,
                        List<Span> spans, TraceData data, OffsetDateTime createdAt) {}

    /** The row as listed by GET /api/traces. */
    public record Summary(UUID id, String kind, Status status, String question, String model, Integer latencyMs, OffsetDateTime createdAt) {}

    public record Page(List<Summary> items, String nextCursor) {}
}
