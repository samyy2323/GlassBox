package dev.glassbox.autotune;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire types of the AutoTune API. */
public final class AutotuneModel {
    private AutotuneModel() {}

    /** Everything but the test set and the name has a default. */
    public record Request(UUID testsetId, String name, String objective, Double qualityFloor, Integer latencyCapMs, Boolean fullStage,
                          Integer stage1Candidates, Integer stage2Candidates, Integer finalists, Integer fullQuestions,
                          Integer capMinutes, Integer capLlmCalls, Integer capSearches, Long seed) {}

    /** The request after defaults and range checks; this is what is stored and what the search reads. */
    public record Options(Objective objective, double qualityFloor, int latencyCapMs, boolean fullStage, int stage1Candidates, int stage2Candidates,
                          int finalists, int fullQuestions, int capMinutes, int capLlmCalls, int capSearches, long seed) {}

    public record Estimate(int tuningQuestions, int heldOutQuestions, int stage1Candidates, int stage2Candidates, int finalists, long retrievalCalls,
                           long llmCalls, List<String> notes) {}

    public record View(UUID id, UUID testsetId, String testsetName, String name, String objective, String status, String stage, Options options,
                       Map<String, Object> split, Map<String, Object> spent, String stoppedBy, String error, UUID experimentId, JsonNode result,
                       Map<String, Integer> candidates, OffsetDateTime createdAt, OffsetDateTime finishedAt) {}

    /** The list row: enough to show a search and open it. */
    public record Summary(UUID id, String name, String testsetName, String objective, String status, String stage, OffsetDateTime createdAt, OffsetDateTime finishedAt,
                          String headline) {}
}
