package dev.glassbox.eval;

import dev.glassbox.retrieval.PipelineConfig;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire types of the experiments API. Absent numbers (no interval for a latency, no value yet) are omitted, never NaN. */
public final class ExperimentModel {
    private ExperimentModel() {}

    /** One configuration as a user writes it; everything but the name has a default. */
    public record ConfigInput(String name, String mode, Integer kFused, Integer rrfK, Double wVec, Double wKw, String keywordMatch,
                              String reranker, Integer topN, Integer tokenBudget) {}

    public record Estimate(int questions, int staleQuestions, int runs, int retrievalCalls, int llmCalls, long estimatedTokens, double estimatedUsd, List<String> notes) {}

    public record MetricView(String metric, String slice, String method, Double value, Double ciLow, Double ciHigh, int n) {}

    public record RunView(UUID id, String label, int ordinal, String status, int samplesDone, PipelineConfig config, List<MetricView> metrics) {}

    public record RunComparison(UUID runId, String label, List<Comparisons.Row> rows) {}

    /** How far the judge behind the judged metrics can be trusted: per metric, from the latest calibration of this judge model and rubric version. */
    public record JudgeMetric(String status, Double kappa, Integer n) {}

    public record JudgeInfo(String model, String rubricVersion, String generationModel, String nliModel, boolean sameModelAsGenerator,
                            Map<String, JudgeMetric> metrics, List<String> warnings) {}

    public record ExperimentView(UUID id, UUID testsetId, String testsetName, int testsetVersion, String name, String status, String kind, String mode,
                                 int questionCount, int staleCount, Map<String, Object> estimatedCost, Map<String, Object> actualCost, String error,
                                 OffsetDateTime createdAt, OffsetDateTime finishedAt, List<RunView> runs, List<RunComparison> vsBaseline, JudgeInfo judge) {}

    public record ResultView(UUID questionId, String question, String qtype, Map<String, Double> metrics, Integer latencyMs, String failureStage,
                             Object retrieved, Object finalContext, String answer, Object claims, UUID traceId) {}

    public record Flip(UUID questionId, String question, String qtype, double a, double b) {}

    public record ComparisonView(UUID runA, UUID runB, String labelA, String labelB, int questions, List<Comparisons.Row> rows,
                                 String flipMetric, List<Flip> passToFail, List<Flip> failToPass) {}
}
