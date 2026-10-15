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
public final class MeasurableQuestions {
    public record Question(UUID id, String qtype, String text, String expected, List<GoldSpan> spans, List<String> flags) {
        public Question(UUID id, String qtype, String text, String expected, List<GoldSpan> spans) {
            this(id, qtype, text, expected, spans, List.of());
        }

        /** Questions from the same document resemble each other, so the first span's document is the cluster for the cluster bootstrap. Null for an unanswerable question. */
        public UUID clusterDocument() {
            return spans.isEmpty() ? null : spans.get(0).documentId();
        }
    }

    public record Loaded(List<Question> questions, int stale, int withoutEvidence) {
        /** Only the questions with these ids, in test set order. */
        public Loaded only(Set<UUID> ids) {
            return new Loaded(questions.stream().filter(q -> ids.contains(q.id())).toList(), stale, withoutEvidence);
        }
    }

    private MeasurableQuestions() {}

    /** Retrieval experiments leave unanswerable questions out (nothing to hit); full experiments keep them to measure abstention. */
    static Loaded load(JdbcTemplate j, ObjectMapper json, UUID testsetId) {
        return load(j, json, testsetId, false);
    }

    public static Loaded load(JdbcTemplate j, ObjectMapper json, UUID testsetId, boolean includeUnanswerable) {
        return load(j, json, testsetId, includeUnanswerable, null);
    }

    /**
     * @param limit when set and smaller than the number of questions, a fixed random sample of that size. The choice depends only on the question ids,
     *              so every run of an experiment, and a resumed job, measures exactly the same questions.
     */
    public static Loaded load(JdbcTemplate j, ObjectMapper json, UUID testsetId, boolean includeUnanswerable, Integer limit) {
        record Row(UUID id, String qtype, String question, String expected, String spans, List<String> flags) {}
        List<Row> rows = j.query("SELECT id, qtype, question, expected_answer, gold_chunk_spans::text AS spans, quality_flags FROM test_questions "
                + "WHERE testset_id = ? AND review_status IN ('ACCEPTED', 'EDITED') ORDER BY seq",
                (rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("qtype"), rs.getString("question"), rs.getString("expected_answer"), rs.getString("spans"),
                        List.of((String[]) rs.getArray("quality_flags").getArray())), testsetId);

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
                if (includeUnanswerable) parsed.add(new Question(r.id(), r.qtype(), r.question(), r.expected(), spans, r.flags()));
                continue;
            }
            spans.forEach(s -> docs.add(s.documentId()));
            parsed.add(new Question(r.id(), r.qtype(), r.question(), r.expected(), spans, r.flags()));
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
        return new Loaded(limit == null ? fresh : sample(fresh, limit), stale, withoutEvidence);
    }

    /** The first {@code limit} questions in an order that looks random but is fixed by the ids, put back in test set order. */
    static List<Question> sample(List<Question> questions, int limit) {
        if (limit >= questions.size()) return questions;
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < questions.size(); i++) order.add(i);
        order.sort(java.util.Comparator.comparingLong((Integer i) -> mix(questions.get(i).id())));
        List<Integer> keep = new ArrayList<>(order.subList(0, limit));
        keep.sort(null);
        List<Question> out = new ArrayList<>();
        for (int i : keep) out.add(questions.get(i));
        return out;
    }

    private static long mix(UUID id) {
        long x = (id.getMostSignificantBits() ^ id.getLeastSignificantBits()) * 0x9E3779B97F4A7C15L;
        return x ^ (x >>> 29);
    }
}
