package dev.glassbox.feedback;

import static dev.glassbox.workspace.WorkspaceService.audit;
import static dev.glassbox.workspace.WorkspaceService.require;

import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.dataset.QualityFilters;
import dev.glassbox.dataset.TestSetModel.Question;
import dev.glassbox.dataset.TestSetModel.SpanInput;
import dev.glassbox.dataset.TestSetService;
import dev.glassbox.diagnose.FailureClassifier.Diagnosis;
import dev.glassbox.diagnose.TraceDiagnosis;
import dev.glassbox.diagnose.TraceDiagnosis.Marked;
import dev.glassbox.embed.EmbeddingProviders;
import dev.glassbox.retrieval.ChunkingResolver;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import dev.glassbox.trace.TraceModel.CandidateView;
import dev.glassbox.trace.TraceModel.Trace;
import dev.glassbox.trace.TraceService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The production feedback loop (SPEC 11, T1.3). A thumbs-down on an answer becomes an inbox item; an editor marks the passage that holds the right answer
 * (or types that the question has no answer), the failure classifier shows which stage of the pipeline lost it, and saving turns it into a test question in
 * a draft version of a chosen test set. Over time the test set grows from real failures, and the share that came from feedback is reported.
 */
@Service
public class FeedbackService {
    public record Item(UUID id, UUID traceId, String question, String answer, String rating, String comment, String status, UUID convertedQuestionId, OffsetDateTime createdAt) {}

    public record Passage(UUID documentId, String title, Integer page, int charStart, int charEnd, String snippet, String disposition, Integer rank) {}

    public record Detail(Item item, boolean traceAvailable, List<String> warnings, List<Passage> retrieved) {}

    public record Diagnosed(String stage, String lostAt, boolean retrievalProblem, String message, List<String> warnings) {}

    public record Converted(Item item, Question question, UUID testsetId, boolean newVersion) {}

    public record Summary(int open, int converted, int dismissed, int down, int up, int questionsFromFeedback, int questionsTotal) {}

    /** Questions closer than this (cosine) to one already in the test set are the same question. */
    static final double DUPLICATE = QualityFilters.DUPLICATE_THRESHOLD;

    private final Tx tx;
    private final TraceService traces;
    private final TestSetService testsets;
    private final ChunkingResolver chunkings;
    private final EmbeddingProviders embedders;

    public FeedbackService(Tx tx, TraceService traces, TestSetService testsets, ChunkingResolver chunkings, EmbeddingProviders embedders) {
        this.tx = tx;
        this.traces = traces;
        this.testsets = testsets;
        this.chunkings = chunkings;
        this.embedders = embedders;
    }

    private static final String COLS = "id, trace_id, question, answer, rating, comment, status, converted_question_id, created_at";

    private static Item item(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Item(rs.getObject("id", UUID.class), rs.getObject("trace_id", UUID.class), rs.getString("question"), rs.getString("answer"), rs.getString("rating"),
                rs.getString("comment"), rs.getString("status"), rs.getObject("converted_question_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    // ------------------------------------------------------------------ giving feedback

    /** Anyone who can ask a question can rate its answer. Rating again changes the rating. */
    public Item give(Principal p, UUID ws, UUID traceId, String rating, String comment) {
        if (!"UP".equals(rating) && !"DOWN".equals(rating)) throw ApiException.badRequest("rating must be UP or DOWN");
        String note = comment == null || comment.isBlank() ? null : comment.strip();
        if (note != null && note.length() > 2000) throw ApiException.badRequest("A comment can have at most 2000 characters");
        Trace t = traces.get(p, ws, traceId); // 404 for another workspace's trace
        if (!"CHAT".equals(t.kind())) throw ApiException.badRequest("Only answers from chat can be rated");
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            UUID id = j.queryForObject("INSERT INTO feedback (workspace_id, trace_id, question, answer, rating, comment, created_by) VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (trace_id, created_by) WHERE trace_id IS NOT NULL DO UPDATE SET rating = EXCLUDED.rating, comment = EXCLUDED.comment, updated_at = now(), "
                            + "status = CASE WHEN feedback.status = 'CONVERTED' THEN 'CONVERTED' WHEN EXCLUDED.rating = 'DOWN' THEN 'OPEN' ELSE 'DISMISSED' END RETURNING id",
                    UUID.class, ws, traceId, t.question(), t.answer(), rating, note, p.userId());
            // praise needs no triage
            j.update("UPDATE feedback SET status = 'DISMISSED' WHERE id = ? AND rating = 'UP' AND status = 'OPEN'", id);
            return j.query("SELECT " + COLS + " FROM feedback WHERE id = ?", FeedbackService::item, id).get(0);
        });
    }

    // ------------------------------------------------------------------ the inbox

    public List<Item> list(Principal p, UUID ws, String status, String rating) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            StringBuilder sql = new StringBuilder("SELECT " + COLS + " FROM feedback WHERE true");
            List<Object> args = new ArrayList<>();
            if (status != null && !status.isBlank()) { sql.append(" AND status = ?"); args.add(status.toUpperCase()); }
            if (rating != null && !rating.isBlank()) { sql.append(" AND rating = ?"); args.add(rating.toUpperCase()); }
            sql.append(" ORDER BY (status = 'OPEN') DESC, created_at DESC LIMIT 200");
            return j.query(sql.toString(), FeedbackService::item, args.toArray());
        });
    }

    public Summary summary(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            var f = j.queryForMap("SELECT count(*) FILTER (WHERE status = 'OPEN') AS open, count(*) FILTER (WHERE status = 'CONVERTED') AS converted, "
                    + "count(*) FILTER (WHERE status = 'DISMISSED') AS dismissed, count(*) FILTER (WHERE rating = 'DOWN') AS down, count(*) FILTER (WHERE rating = 'UP') AS up FROM feedback");
            // the latest version of every test set, rejected questions left out
            var q = j.queryForMap("SELECT count(*) FILTER (WHERE q.origin = 'FEEDBACK') AS fb, count(*) AS total FROM test_questions q JOIN testsets t ON t.id = q.testset_id "
                    + "WHERE q.review_status <> 'REJECTED' AND t.version = (SELECT max(version) FROM testsets x WHERE x.name = t.name)");
            return new Summary(num(f, "open"), num(f, "converted"), num(f, "dismissed"), num(f, "down"), num(f, "up"), num(q, "fb"), num(q, "total"));
        });
    }

    private static int num(java.util.Map<String, Object> m, String k) {
        return ((Number) m.get(k)).intValue();
    }

    public Detail detail(Principal p, UUID ws, UUID id) {
        Item it = load(p, ws, id, Role.EDITOR);
        if (it.traceId() == null) return new Detail(it, false, List.of("The trace of this answer has been removed by the retention policy; the question and answer are kept."), List.of());
        Trace t = traces.get(p, ws, it.traceId());
        List<Passage> passages = new ArrayList<>();
        for (CandidateView c : t.data().candidates()) {
            passages.add(new Passage(c.documentId(), c.title(), c.page(), c.charStart(), c.charEnd(), c.snippet(), c.disposition().name(), c.rankedPosition()));
        }
        return new Detail(it, true, TraceDiagnosis.warnings(t), passages);
    }

    private Item load(Principal p, UUID ws, UUID id, Role role) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, role);
            var rows = j.query("SELECT " + COLS + " FROM feedback WHERE id = ?", FeedbackService::item, id);
            if (rows.isEmpty()) throw ApiException.notFound("No such feedback");
            return rows.get(0);
        });
    }

    // ------------------------------------------------------------------ which stage lost it

    public Diagnosed diagnose(Principal p, UUID ws, UUID id, List<SpanInput> marked) {
        Item it = load(p, ws, id, Role.EDITOR);
        if (it.traceId() == null) throw ApiException.conflict("The trace of this answer is gone, so there is nothing to diagnose.");
        Trace t = traces.get(p, ws, it.traceId());
        List<Marked> spans = covered(ws, t, marked == null ? List.of() : marked);
        TraceDiagnosis.Result r = TraceDiagnosis.diagnose(t, spans);
        Diagnosis d = r.diagnosis();
        return new Diagnosed(d == null ? null : d.stage().name(), d == null || d.lostAt() == null ? null : d.lostAt().name(), TraceDiagnosis.retrievalProblem(d), r.message(), r.warnings());
    }

    /** Looks up, for every marked passage that did not reach the prompt, the largest share of it that one chunk of the trace's chunking covers. */
    private List<Marked> covered(UUID ws, Trace t, List<SpanInput> marked) {
        UUID chunking = chunkings.resolve(ws, t.data().config() == null ? null : t.data().config().chunking()).orElse(null);
        List<Marked> out = new ArrayList<>();
        for (SpanInput s : marked) {
            if (s.documentId() == null || s.charStart() == null || s.charEnd() == null || s.charEnd() <= s.charStart()) throw ApiException.badRequest("Every marked passage needs a document and a character range");
            double cover = chunking == null ? 0 : tx.workspace(ws, null, j -> bestCover(j, chunking, s));
            out.add(new Marked(s.documentId(), s.charStart(), s.charEnd(), cover));
        }
        return out;
    }

    private static double bestCover(JdbcTemplate j, UUID chunking, SpanInput s) {
        int length = s.charEnd() - s.charStart();
        Integer overlap = j.queryForObject("SELECT coalesce(max(least(char_end, ?) - greatest(char_start, ?)), 0) FROM chunks WHERE chunking_id = ? AND document_id = ? AND NOT superseded "
                + "AND char_end > ? AND char_start < ?", Integer.class, s.charEnd(), s.charStart(), chunking, s.documentId(), s.charStart(), s.charEnd());
        return Math.min(1.0, Math.max(0, overlap == null ? 0 : overlap) / (double) length);
    }

    // ------------------------------------------------------------------ into the test set

    /**
     * Turns an inbox item into a test question in a draft of the chosen test set (a final version is copied to a new draft first). Refused when the set already
     * has a question that means the same, and when the item was converted before.
     */
    public Converted convert(Principal p, UUID ws, UUID id, UUID testsetId, String expectedAnswer, String qtype, List<SpanInput> evidence, String questionOverride) {
        Item it = load(p, ws, id, Role.EDITOR);
        if (!"OPEN".equals(it.status())) throw ApiException.conflict("This feedback is already " + it.status().toLowerCase() + ".");
        String question = questionOverride == null || questionOverride.isBlank() ? it.question() : questionOverride.strip();
        String type = qtype == null || qtype.isBlank() ? (evidence == null || evidence.isEmpty() ? "UNANSWERABLE" : "FACTUAL") : qtype;
        if (evidence == null || evidence.isEmpty()) {
            if (!"UNANSWERABLE".equals(type)) throw ApiException.badRequest("Mark the passage that holds the answer, or say that the question has no answer in the documents");
        }
        String expected = expectedAnswer == null || expectedAnswer.isBlank() ? ("UNANSWERABLE".equals(type) ? "Not stated in the documents." : null) : expectedAnswer.strip();
        if (expected == null) throw ApiException.badRequest("Type the expected answer");

        var set = tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            var rows = j.queryForList("SELECT status, name FROM testsets WHERE id = ?", testsetId);
            if (rows.isEmpty()) throw ApiException.notFound("No such test set");
            return rows.get(0);
        });
        String status = (String) set.get("status");
        if (!status.equals("FINAL") && !status.equals("DRAFT")) throw ApiException.conflict("That test set is " + status.toLowerCase() + " and cannot take a question now.");

        UUID duplicateOf = duplicate(ws, p, testsetId, question);
        if (duplicateOf != null) throw ApiException.conflict("The test set already has a question that means the same. Dismiss this item, or edit that question instead.");

        boolean fresh = status.equals("FINAL");
        UUID target = fresh ? testsets.newVersion(p, ws, testsetId).id() : testsetId;
        Question q = testsets.addFromFeedback(p, ws, target, question, expected, type, evidence, it.traceId());
        Item done = tx.workspace(ws, p.userId(), j -> {
            j.update("UPDATE feedback SET status = 'CONVERTED', converted_question_id = ?, updated_at = now() WHERE id = ?", q.id(), id);
            audit(j, ws, p.actor(), "feedback.convert", "feedback:" + id + " question:" + q.id());
            return j.query("SELECT " + COLS + " FROM feedback WHERE id = ?", FeedbackService::item, id).get(0);
        });
        return new Converted(done, q, target, fresh);
    }

    /** Id of a non-rejected question of the set that is a near-copy of {@code question}, or null. */
    private UUID duplicate(UUID ws, Principal p, UUID testsetId, String question) {
        var existing = tx.workspace(ws, p.userId(), j -> j.query("SELECT id, question FROM test_questions WHERE testset_id = ? AND review_status <> 'REJECTED'",
                (rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2)}, testsetId));
        if (existing.isEmpty()) return null;
        var embedder = embedders.get(embedders.defaultModel());
        List<String> texts = new ArrayList<>();
        texts.add(question);
        for (Object[] e : existing) texts.add((String) e[1]);
        List<float[]> vectors = embedder.embed(texts);
        for (int i = 0; i < existing.size(); i++) {
            if (QualityFilters.cosine(vectors.get(0), vectors.get(i + 1)) > DUPLICATE) return (UUID) existing.get(i)[0];
        }
        return null;
    }

    public Item dismiss(Principal p, UUID ws, UUID id) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            int n = j.update("UPDATE feedback SET status = 'DISMISSED', updated_at = now() WHERE id = ? AND status = 'OPEN'", id);
            var rows = j.query("SELECT " + COLS + " FROM feedback WHERE id = ?", FeedbackService::item, id);
            if (rows.isEmpty()) throw ApiException.notFound("No such feedback");
            if (n == 0) throw ApiException.conflict("This feedback is already " + rows.get(0).status().toLowerCase() + ".");
            return rows.get(0);
        });
    }
}
