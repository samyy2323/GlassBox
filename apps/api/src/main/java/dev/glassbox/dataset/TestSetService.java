package dev.glassbox.dataset;

import static dev.glassbox.workspace.WorkspaceService.audit;
import static dev.glassbox.workspace.WorkspaceService.require;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.dataset.TestSetModel.*;
import dev.glassbox.jobs.JobQueue;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * Test set lifecycle. A version is DRAFT while it is generated and reviewed, then FINAL (immutable). Changing a final
 * version means creating a new DRAFT copy; questions keep their {@code stable_id} across copies so a diff can tell
 * added, removed and edited questions apart.
 */
@Service
public class TestSetService {
    static final int MAX_QUESTIONS = 200;
    private static final Set<String> MANUAL_TYPES = Set.of("FACTUAL", "NUMERIC", "MULTI_HOP", "COMPARISON", "UNANSWERABLE");

    private final Tx tx;
    private final ObjectMapper json;
    private final JobQueue queue;
    private final boolean llmFilters;

    public TestSetService(Tx tx, ObjectMapper json, JobQueue queue, @Value("${glassbox.testset.llm-filters}") boolean llmFilters) {
        this.tx = tx;
        this.json = json;
        this.queue = queue;
        this.llmFilters = llmFilters;
    }

    // ------------------------------------------------------------------ generation

    public GenerateResult generate(Principal p, UUID ws, String name, Integer count, Map<String, Double> mix, List<UUID> documentIds, Long seed) {
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isEmpty() || cleanName.length() > 120) throw ApiException.badRequest("A test set needs a name of 1 to 120 characters");
        int target = count == null ? 50 : count;
        if (target < 1 || target > MAX_QUESTIONS) throw ApiException.badRequest("Ask for between 1 and " + MAX_QUESTIONS + " questions");
        Map<String, Double> useMix = new LinkedHashMap<>();
        if (mix != null) {
            for (var e : mix.entrySet()) {
                if (!QuestionSampler.TYPES.contains(e.getKey())) {
                    throw ApiException.badRequest("Question type " + e.getKey() + " cannot be generated yet. Available: " + QuestionSampler.TYPES);
                }
                if (e.getValue() == null || e.getValue() < 0) throw ApiException.badRequest("Type shares must be zero or more");
                if (e.getValue() > 0) useMix.put(e.getKey(), e.getValue());
            }
        }
        if (useMix.isEmpty()) useMix.putAll(QuestionSampler.defaultMix());
        long useSeed = seed != null ? seed : ThreadLocalRandom.current().nextLong(1, 1_000_000_000L);

        ObjectNode params = json.createObjectNode();
        params.put("count", target);
        params.put("seed", useSeed);
        params.set("mix", json.valueToTree(useMix));
        if (documentIds != null && !documentIds.isEmpty()) params.set("documentIds", json.valueToTree(documentIds));
        params.put("llmFilters", llmFilters);

        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            if (!j.queryForList("SELECT 1 FROM testsets WHERE name = ?", cleanName).isEmpty()) {
                throw ApiException.conflict("A test set named \"" + cleanName + "\" already exists. Create a new version of it instead.");
            }
            int[] ready = {0};
            if (documentIds != null && !documentIds.isEmpty()) {
                j.query("SELECT count(*) FROM documents WHERE status = 'READY' AND id = ANY (?)",
                        ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", documentIds.toArray())), rs -> { ready[0] = rs.getInt(1); });
            } else {
                ready[0] = j.queryForObject("SELECT count(*) FROM documents WHERE status = 'READY'", Integer.class);
            }
            if (ready[0] == 0) throw ApiException.unprocessable("Upload documents and wait until they are ready before generating a test set");
            UUID id = j.queryForObject("INSERT INTO testsets (workspace_id, name, status, params, target_count, created_by) "
                    + "VALUES (?, ?, 'GENERATING', ?::jsonb, ?, ?) RETURNING id", UUID.class, ws, cleanName, params.toString(), target, p.userId());
            queue.enqueue(j, ws, GenerateTestsetHandler.TYPE, json.createObjectNode().put("testsetId", id.toString()), "testset-" + id);
            audit(j, ws, p.actor(), "testset.generate", "testset:" + id);
            return new GenerateResult(load(j, id), target * (llmFilters ? 3 : 1));
        });
    }

    // ------------------------------------------------------------------ reading

    public List<TestSet> list(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            List<UUID> ids = j.queryForList("SELECT id FROM testsets ORDER BY created_at DESC, id DESC", UUID.class);
            List<TestSet> out = new ArrayList<>();
            for (UUID id : ids) out.add(load(j, id));
            return out;
        });
    }

    public TestSet get(Principal p, UUID ws, UUID id) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            return load(j, id);
        });
    }

    private TestSet load(JdbcTemplate j, UUID id) {
        var rows = j.query("SELECT id, name, version, parent_version_id, status, target_count, generated_count, error, created_at, finalized_at "
                + "FROM testsets WHERE id = ?", (rs, n) -> new TestSet(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("version"),
                rs.getObject("parent_version_id", UUID.class), rs.getString("status"), rs.getInt("target_count"), rs.getInt("generated_count"),
                rs.getString("error"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("finalized_at", OffsetDateTime.class), null), id);
        if (rows.isEmpty()) throw ApiException.notFound("No such test set");
        TestSet t = rows.get(0);
        return new TestSet(t.id(), t.name(), t.version(), t.parentVersionId(), t.status(), t.targetCount(), t.generatedCount(), t.error(),
                t.createdAt(), t.finalizedAt(), stats(j, id));
    }

    static Double acceptanceRate(int accepted, int edited, int rejected) {
        int reviewed = accepted + edited + rejected;
        return reviewed == 0 ? null : (double) (accepted + edited) / reviewed;
    }

    private Stats stats(JdbcTemplate j, UUID id) {
        Map<String, Integer> status = new HashMap<>();
        j.query("SELECT review_status, count(*) AS n FROM test_questions WHERE testset_id = ? GROUP BY review_status",
                rs -> { status.put(rs.getString(1), rs.getInt(2)); }, id);
        Map<String, Integer> byType = new LinkedHashMap<>();
        j.query("SELECT qtype, count(*) FROM test_questions WHERE testset_id = ? GROUP BY qtype ORDER BY qtype", rs -> { byType.put(rs.getString(1), rs.getInt(2)); }, id);
        Map<String, Integer> byFlag = new LinkedHashMap<>();
        j.query("SELECT f, count(*) FROM test_questions, unnest(quality_flags) AS f WHERE testset_id = ? GROUP BY f ORDER BY f", rs -> { byFlag.put(rs.getString(1), rs.getInt(2)); }, id);
        int pending = status.getOrDefault("PENDING", 0), accepted = status.getOrDefault("ACCEPTED", 0);
        int edited = status.getOrDefault("EDITED", 0), rejected = status.getOrDefault("REJECTED", 0);
        return new Stats(pending + accepted + edited + rejected, pending, accepted, edited, rejected, acceptanceRate(accepted, edited, rejected), byType, byFlag);
    }

    private static final String Q_COLS = "id, stable_id, testset_id, question, expected_answer, qtype, origin, review_status, quality_flags, gold_chunk_spans::text AS spans, created_at, seq";

    public QuestionPage questions(Principal p, UUID ws, UUID setId, String status, String qtype, String flag, int limit, String cursor) {
        int size = Math.max(1, Math.min(limit, 200));
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            load(j, setId);
            StringBuilder sql = new StringBuilder("SELECT " + Q_COLS + " FROM test_questions WHERE testset_id = ?");
            List<Object> args = new ArrayList<>(List.of(setId));
            if (status != null && !status.isBlank()) {
                sql.append(" AND review_status = ?");
                args.add(status);
            }
            if (qtype != null && !qtype.isBlank()) {
                sql.append(" AND qtype = ?");
                args.add(qtype);
            }
            if (flag != null && !flag.isBlank()) {
                if (flag.equals("NONE")) sql.append(" AND cardinality(quality_flags) = 0");
                else if (flag.equals("ANY")) sql.append(" AND cardinality(quality_flags) > 0");
                else {
                    sql.append(" AND ? = ANY (quality_flags)");
                    args.add(flag);
                }
            }
            if (cursor != null && !cursor.isBlank()) {
                try {
                    args.add(Long.parseLong(cursor));
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("Invalid cursor");
                }
                sql.append(" AND seq > ?");
            }
            sql.append(" ORDER BY seq LIMIT ?");
            args.add(size + 1);
            List<Raw> rows = j.query(sql.toString(), RAW, args.toArray());
            boolean more = rows.size() > size;
            List<Raw> page = more ? rows.subList(0, size) : rows;
            return new QuestionPage(hydrate(j, page), more ? String.valueOf(page.get(size - 1).seq()) : null);
        });
    }

    private record Raw(UUID id, UUID stableId, UUID testsetId, String question, String answer, String qtype, String origin, String status,
                       List<String> flags, String spans, OffsetDateTime createdAt, long seq) {}

    private static final RowMapper<Raw> RAW = (rs, n) -> new Raw(rs.getObject("id", UUID.class), rs.getObject("stable_id", UUID.class),
            rs.getObject("testset_id", UUID.class), rs.getString("question"), rs.getString("expected_answer"), rs.getString("qtype"),
            rs.getString("origin"), rs.getString("review_status"), List.of((String[]) rs.getArray("quality_flags").getArray()), rs.getString("spans"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getLong("seq"));

    private List<Question> hydrate(JdbcTemplate j, List<Raw> rows) {
        List<List<GoldSpan>> spans = new ArrayList<>();
        Set<UUID> docs = new LinkedHashSet<>();
        for (Raw r : rows) {
            List<GoldSpan> s = readSpans(r.spans());
            spans.add(s);
            s.forEach(x -> docs.add(x.documentId()));
        }
        Map<UUID, String> titles = new HashMap<>();
        if (!docs.isEmpty()) {
            j.query("SELECT id, title FROM documents WHERE id = ANY (?)", ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", docs.toArray())),
                    rs -> { titles.put(rs.getObject(1, UUID.class), rs.getString(2)); });
        }
        List<Question> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Raw r = rows.get(i);
            List<Evidence> ev = spans.get(i).stream().map(s -> new Evidence(s.documentId(), titles.getOrDefault(s.documentId(), "(deleted document)"),
                    s.charStart(), s.charEnd(), s.text())).toList();
            out.add(new Question(r.id(), r.stableId(), r.testsetId(), r.question(), r.answer(), r.qtype(), r.origin(), r.status(), r.flags(), ev, r.createdAt()));
        }
        return out;
    }

    private List<GoldSpan> readSpans(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<GoldSpan>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored gold spans are unreadable", e);
        }
    }

    // ------------------------------------------------------------------ review

    private String statusOf(JdbcTemplate j, UUID setId) {
        var rows = j.queryForList("SELECT status FROM testsets WHERE id = ? FOR UPDATE", String.class, setId);
        if (rows.isEmpty()) throw ApiException.notFound("No such test set");
        return rows.get(0);
    }

    private static void requireDraft(String status) {
        if (status.equals("FINAL")) throw ApiException.conflict("A final test set cannot be changed. Create a new version to edit it.");
        if (!status.equals("DRAFT")) throw ApiException.conflict("This test set is " + status.toLowerCase() + ", not ready for review");
    }

    /** Reviewer actions: accept, reject, put back to pending, and/or edit text or evidence. Any edit marks the question EDITED. */
    public Question patch(Principal p, UUID ws, UUID questionId, String question, String expected, String reviewStatus, List<SpanInput> spans, String qtype) {
        if (reviewStatus != null && !Set.of("ACCEPTED", "REJECTED", "PENDING").contains(reviewStatus)) {
            throw ApiException.badRequest("reviewStatus must be ACCEPTED, REJECTED or PENDING (edits are marked automatically)");
        }
        if (qtype != null && !MANUAL_TYPES.contains(qtype)) throw ApiException.badRequest("qtype must be one of " + MANUAL_TYPES);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            var setIds = j.queryForList("SELECT testset_id FROM test_questions WHERE id = ?", UUID.class, questionId);
            if (setIds.isEmpty()) throw ApiException.notFound("No such question");
            requireDraft(statusOf(j, setIds.get(0)));
            Raw current = j.query("SELECT " + Q_COLS + " FROM test_questions WHERE id = ? FOR UPDATE", RAW, questionId).get(0);

            String newQuestion = question == null ? current.question() : cleanText(question, "question", 600);
            String newAnswer = expected == null ? current.answer() : cleanText(expected, "expectedAnswer", 2000);
            List<GoldSpan> oldSpans = readSpans(current.spans());
            List<GoldSpan> newSpanList = spans == null ? oldSpans : resolveSpans(j, spans);
            String newSpans = writeSpans(newSpanList);
            String newType = qtype == null ? current.qtype() : qtype;
            boolean contentChanged = !newQuestion.equals(current.question()) || !newAnswer.equals(current.answer()) || !samePlaces(oldSpans, newSpanList)
                    || !newType.equals(current.qtype());
            String status = reviewStatus != null ? reviewStatus : current.status();
            if (contentChanged && !"REJECTED".equals(status)) status = "EDITED";
            j.update("UPDATE test_questions SET question = ?, expected_answer = ?, gold_chunk_spans = ?::jsonb, qtype = ?, review_status = ? WHERE id = ?",
                    newQuestion, newAnswer, newSpans, newType, status, questionId);
            Raw updated = j.query("SELECT " + Q_COLS + " FROM test_questions WHERE id = ?", RAW, questionId).get(0);
            return hydrate(j, List.of(updated)).get(0);
        });
    }

    public Question addManual(Principal p, UUID ws, UUID setId, String question, String expected, String qtype, List<SpanInput> spans) {
        if (qtype == null || !MANUAL_TYPES.contains(qtype)) throw ApiException.badRequest("qtype must be one of " + MANUAL_TYPES);
        String q = cleanText(question, "question", 600);
        String a = cleanText(expected, "expectedAnswer", 2000);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            requireDraft(statusOf(j, setId));
            List<GoldSpan> resolved = resolveSpans(j, spans == null ? List.of() : spans);
            if (resolved.isEmpty() && !qtype.equals("UNANSWERABLE")) throw ApiException.badRequest("Select the evidence text this question is answered from");
            UUID id = j.queryForObject("INSERT INTO test_questions (workspace_id, testset_id, question, expected_answer, gold_chunk_spans, qtype, origin, review_status) "
                    + "VALUES (?, ?, ?, ?, ?::jsonb, ?, 'MANUAL', 'ACCEPTED') RETURNING id", UUID.class, ws, setId, q, a, writeSpans(resolved), qtype);
            audit(j, ws, p.actor(), "testset.question.add", "question:" + id);
            return hydrate(j, j.query("SELECT " + Q_COLS + " FROM test_questions WHERE id = ?", RAW, id)).get(0);
        });
    }

    /** Accepts every pending question that has no quality flag. */
    public int bulkAccept(Principal p, UUID ws, UUID setId) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            requireDraft(statusOf(j, setId));
            int n = j.update("UPDATE test_questions SET review_status = 'ACCEPTED' WHERE testset_id = ? AND review_status = 'PENDING' AND cardinality(quality_flags) = 0", setId);
            audit(j, ws, p.actor(), "testset.bulk-accept", "testset:" + setId + " n=" + n);
            return n;
        });
    }

    public TestSet finalizeSet(Principal p, UUID ws, UUID setId, boolean allowPending) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            requireDraft(statusOf(j, setId));
            int pending = j.queryForObject("SELECT count(*) FROM test_questions WHERE testset_id = ? AND review_status = 'PENDING'", Integer.class, setId);
            if (pending > 0 && !allowPending) {
                throw ApiException.conflict(pending + " question(s) are still pending review. Review them, or finalize with allowPending to leave them out.");
            }
            int usable = j.queryForObject("SELECT count(*) FROM test_questions WHERE testset_id = ? AND review_status IN ('ACCEPTED', 'EDITED')", Integer.class, setId);
            if (usable == 0) throw ApiException.conflict("There is no accepted question yet, so there is nothing to finalize");
            j.update("UPDATE testsets SET status = 'FINAL', finalized_at = now() WHERE id = ?", setId);
            audit(j, ws, p.actor(), "testset.finalize", "testset:" + setId);
            return load(j, setId);
        });
    }

    /** New editable DRAFT copy of a FINAL version. Rejected questions are left behind; the rest keep their stable ids and status. */
    public TestSet newVersion(Principal p, UUID ws, UUID fromId) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            var src = j.queryForList("SELECT name, status, params::text AS params FROM testsets WHERE id = ? FOR UPDATE", fromId);
            if (src.isEmpty()) throw ApiException.notFound("No such test set");
            if (!"FINAL".equals(src.get(0).get("status"))) throw ApiException.conflict("Only a final version can be copied; a draft can be edited directly.");
            String name = (String) src.get(0).get("name");
            if (!j.queryForList("SELECT 1 FROM testsets WHERE name = ? AND status IN ('DRAFT', 'GENERATING')", name).isEmpty()) {
                throw ApiException.conflict("\"" + name + "\" already has a draft version. Finish or finalize it first.");
            }
            int version = j.queryForObject("SELECT max(version) + 1 FROM testsets WHERE name = ?", Integer.class, name);
            UUID id = j.queryForObject("INSERT INTO testsets (workspace_id, name, version, parent_version_id, status, params, created_by) "
                    + "VALUES (?, ?, ?, ?, 'DRAFT', ?::jsonb, ?) RETURNING id", UUID.class, ws, name, version, fromId, src.get(0).get("params"), p.userId());
            int copied = j.update("INSERT INTO test_questions (workspace_id, testset_id, stable_id, question, expected_answer, gold_chunk_spans, qtype, origin, "
                    + "review_status, quality_flags, source_trace_id, meta) SELECT workspace_id, ?, stable_id, question, expected_answer, gold_chunk_spans, qtype, "
                    + "origin, review_status, quality_flags, source_trace_id, meta FROM test_questions WHERE testset_id = ? AND review_status <> 'REJECTED' ORDER BY seq", id, fromId);
            j.update("UPDATE testsets SET target_count = ?, generated_count = ? WHERE id = ?", copied, copied, id);
            audit(j, ws, p.actor(), "testset.version", "testset:" + id + " from " + fromId);
            return load(j, id);
        });
    }

    /** What changed in {@code setId} compared with {@code against}: questions are matched by stable id, rejected ones are ignored. */
    public Diff diff(Principal p, UUID ws, UUID setId, UUID against) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            load(j, setId);
            load(j, against);
            Map<UUID, Question> now = byStable(j, setId), before = byStable(j, against);
            List<Question> added = new ArrayList<>(), removed = new ArrayList<>();
            List<Edited> edited = new ArrayList<>();
            int same = 0;
            for (var e : now.entrySet()) {
                Question old = before.get(e.getKey());
                if (old == null) added.add(e.getValue());
                else if (!sameContent(old, e.getValue())) edited.add(new Edited(old, e.getValue()));
                else same++;
            }
            for (var e : before.entrySet()) if (!now.containsKey(e.getKey())) removed.add(e.getValue());
            return new Diff(setId, against, added, removed, edited, same);
        });
    }

    private Map<UUID, Question> byStable(JdbcTemplate j, UUID setId) {
        List<Raw> rows = j.query("SELECT " + Q_COLS + " FROM test_questions WHERE testset_id = ? AND review_status <> 'REJECTED' ORDER BY seq", RAW, setId);
        Map<UUID, Question> out = new LinkedHashMap<>();
        for (Question q : hydrate(j, rows)) out.put(q.stableId(), q);
        return out;
    }

    private static boolean sameContent(Question a, Question b) {
        return a.question().equals(b.question()) && a.expectedAnswer().equals(b.expectedAnswer()) && a.qtype().equals(b.qtype())
                && a.evidence().stream().map(e -> e.documentId() + ":" + e.charStart() + "-" + e.charEnd()).toList()
                .equals(b.evidence().stream().map(e -> e.documentId() + ":" + e.charStart() + "-" + e.charEnd()).toList());
    }

    // ------------------------------------------------------------------ helpers

    private static boolean samePlaces(List<GoldSpan> a, List<GoldSpan> b) {
        return a.stream().map(x -> x.documentId() + ":" + x.charStart() + "-" + x.charEnd()).toList()
                .equals(b.stream().map(x -> x.documentId() + ":" + x.charStart() + "-" + x.charEnd()).toList());
    }

    private static String cleanText(String text, String field, int max) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) throw ApiException.badRequest(field + " must not be empty");
        if (t.length() > max) throw ApiException.badRequest(field + " is longer than " + max + " characters");
        return t;
    }

    private String writeSpans(List<GoldSpan> spans) {
        try {
            return json.writeValueAsString(spans);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Validates reviewer-chosen evidence against the document's current text and fills in the snippet. */
    private List<GoldSpan> resolveSpans(JdbcTemplate j, List<SpanInput> inputs) {
        List<GoldSpan> out = new ArrayList<>();
        for (SpanInput s : inputs) {
            if (s.documentId() == null || s.charStart() == null || s.charEnd() == null) throw ApiException.badRequest("Each evidence span needs documentId, charStart and charEnd");
            var rows = j.queryForList("SELECT v.id, v.normalized_text FROM documents d JOIN document_versions v ON v.document_id = d.id AND v.version = d.version "
                    + "WHERE d.id = ? AND d.status = 'READY'", s.documentId());
            if (rows.isEmpty() || rows.get(0).get("normalized_text") == null) throw ApiException.unprocessable("Unknown or unprocessed document in the evidence");
            String text = (String) rows.get(0).get("normalized_text");
            if (s.charStart() < 0 || s.charEnd() > text.length() || s.charStart() >= s.charEnd()) {
                throw ApiException.unprocessable("Evidence range " + s.charStart() + "-" + s.charEnd() + " is outside the document");
            }
            out.add(new GoldSpan(s.documentId(), (UUID) rows.get(0).get("id"), s.charStart(), s.charEnd(), GoldSpan.snippet(text, s.charStart(), s.charEnd())));
        }
        return out;
    }
}
