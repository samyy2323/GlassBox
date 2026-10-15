package dev.glassbox.dataset;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire types of the test set API. */
public final class TestSetModel {
    private TestSetModel() {}

    public record Stats(int total, int pending, int accepted, int edited, int rejected, Double acceptanceRate,
                        Map<String, Integer> byType, Map<String, Integer> byFlag, int fromFeedback) {}

    public record TestSet(UUID id, String name, int version, UUID parentVersionId, String status, int targetCount, int generatedCount,
                          String error, OffsetDateTime createdAt, OffsetDateTime finalizedAt, Stats stats) {}

    /** A gold span as shown to a reviewer: the document's title and the evidence text come along. */
    public record Evidence(UUID documentId, String title, int charStart, int charEnd, String text) {}

    public record Question(UUID id, UUID stableId, UUID testsetId, String question, String expectedAnswer, String qtype, String origin,
                           String reviewStatus, List<String> flags, List<Evidence> evidence, OffsetDateTime createdAt) {}

    public record QuestionPage(List<Question> items, String cursor) {}

    public record GenerateResult(TestSet testset, int estimatedLlmCalls) {}

    public record SpanInput(UUID documentId, Integer charStart, Integer charEnd) {}

    public record Edited(Question before, Question after) {}

    public record Diff(UUID testset, UUID against, List<Question> added, List<Question> removed, List<Edited> edited, int unchanged) {}
}
