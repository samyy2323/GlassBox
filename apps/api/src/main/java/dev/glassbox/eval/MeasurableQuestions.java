package dev.glassbox.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.dataset.GoldSpan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The questions of a test set version that a retrieval experiment can measure: accepted or edited, with gold evidence, and whose
 * evidence still exists. A question whose document was replaced or deleted since the test set was written is "stale": its character
 * ranges no longer mean anything, so it is counted and reported but not run.
 */
final class MeasurableQuestions {
    record Question(UUID id, String qtype, String text, List<GoldSpan> spans) {
        /** Questions from the same document resemble each other, so the first span's document is the cluster for the cluster bootstrap. */
        UUID clusterDocument() {
            return spans.get(0).documentId();
        }
    }

    record Loaded(List<Question> questions, int stale, int withoutEvidence) {}

    private MeasurableQuestions() {}

    static Loaded load(JdbcTemplate j, ObjectMapper json, UUID testsetId) {
        record Row(UUID id, String qtype, String question, String spans) {}
        List<Row> rows = j.query("SELECT id, qtype, question, gold_chunk_spans::text AS spans FROM test_questions "
                + "WHERE testset_id = ? AND review_status IN ('ACCEPTED', 'EDITED') ORDER BY seq",
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("qtype"), rs.getString("question"), rs.getString("spans")), testsetId);

        List<Question> parsed = new ArrayList<>();
        Set<UUID> docs = new HashSet<>();
        int withoutEvidence = 0;
        for (Row r : rows) {
            List<GoldSpan> spans;
            try {
                spans = json.readValue(r.spans(), new TypeReference<List<GoldSpan>>() {});
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("stored gold spans are unreadable for question " + r.id(), e);
            }
            if (spans.isEmpty()) {
                withoutEvidence++; // an unanswerable question: no gold span to hit
                continue;
            }
            spans.forEach(s -> docs.add(s.documentId()));
            parsed.add(new Question(r.id(), r.qtype(), r.question(), spans));
        }

        Map<UUID, UUID> currentVersion = new HashMap<>();
        if (!docs.isEmpty()) {
            j.query("SELECT d.id, v.id FROM documents d JOIN document_versions v ON v.document_id = d.id AND v.version = d.version WHERE d.id = ANY (?)",
                    ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", docs.toArray())),
                    rs -> { currentVersion.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)); });
        }
        List<Question> fresh = new ArrayList<>();
        int stale = 0;
        for (Question q : parsed) {
            boolean ok = q.spans().stream().allMatch(s -> s.versionId().equals(currentVersion.get(s.documentId())));
            if (ok) fresh.add(q);
            else stale++;
        }
        return new Loaded(fresh, stale, withoutEvidence);
    }
}
