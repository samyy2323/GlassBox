package dev.glassbox.documents;

import static dev.glassbox.workspace.WorkspaceService.require;

import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.eval.Comparisons;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The versions of a document, what changed between two of them, and what the change did to the test set and to the measured quality. */
@Service
public class ImpactService {
    public record Version(int version, String contentHash, OffsetDateTime createdAt, boolean current, int characters) {}

    public record DiffView(int from, int to, TextDiff.Summary summary, List<TextDiff.Op> ops) {}

    public record Flip(UUID questionId, String question, String failureStage) {}

    public record Verdict(double before, double after, double delta, double ciLow, double ciHigh, double pAdjusted, String text, int questions) {}

    public record StaleQuestion(UUID questionId, String question) {}

    public record Report(int version, String status, String note, UUID testsetId, Integer testsetVersion, UUID experimentId, String experimentStatus, int relocated, int stale,
                         List<StaleQuestion> staleQuestions, Verdict verdict, List<Flip> passToFail, int failToPass, DiffView diff) {}

    private static final int MAX_OP_CHARS = 400;

    private final Tx tx;

    public ImpactService(Tx tx) {
        this.tx = tx;
    }

    public List<Version> versions(Principal p, UUID ws, UUID docId) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            if (j.queryForList("SELECT 1 FROM documents WHERE id = ?", docId).isEmpty()) throw ApiException.notFound("No such document");
            return j.query("SELECT v.version, v.content_hash, v.created_at, v.version = d.version AS current, coalesce(length(v.normalized_text), 0) AS chars "
                    + "FROM document_versions v JOIN documents d ON d.id = v.document_id WHERE v.document_id = ? ORDER BY v.version DESC",
                    (rs, n) -> new Version(rs.getInt("version"), rs.getString("content_hash"), rs.getObject("created_at", OffsetDateTime.class), rs.getBoolean("current"), rs.getInt("chars")), docId);
        });
    }

    /** The paragraph diff of version {@code to} against the one before it. */
    public DiffView diff(Principal p, UUID ws, UUID docId, int to) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            return diff(j, docId, to);
        });
    }

    private DiffView diff(org.springframework.jdbc.core.JdbcTemplate j, UUID docId, int to) {
        var rows = j.queryForList("SELECT version, normalized_text FROM document_versions WHERE document_id = ? AND version IN (?, ?) ORDER BY version", docId, to - 1, to);
        if (rows.size() < 2) throw ApiException.notFound("There is no earlier version to compare with");
        var ops = TextDiff.diff((String) rows.get(0).get("normalized_text"), (String) rows.get(1).get("normalized_text"));
        List<TextDiff.Op> shown = ops.stream().map(o -> new TextDiff.Op(o.kind(), o.text().length() > MAX_OP_CHARS ? o.text().substring(0, MAX_OP_CHARS) + "..." : o.text())).toList();
        return new DiffView(to - 1, to, TextDiff.summarize(ops), shown);
    }

    public Report impact(Principal p, UUID ws, UUID docId, int version) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            var rows = j.queryForList("SELECT i.*, t.version AS testset_version, e.status AS experiment_status, e.question_count FROM change_impacts i LEFT JOIN testsets t ON t.id = i.testset_id "
                    + "LEFT JOIN experiments e ON e.id = i.experiment_id WHERE i.document_id = ? AND i.version = ?", docId, version);
            DiffView diff;
            try {
                diff = diff(j, docId, version);
            } catch (ApiException e) {
                diff = null;
            }
            if (rows.isEmpty()) {
                if (diff == null) throw ApiException.notFound("No such version");
                return new Report(version, "PENDING", "The impact of this change has not been worked out yet.", null, null, null, null, 0, 0, List.of(), null, List.of(), 0, diff);
            }
            var r = rows.get(0);
            UUID setId = (UUID) r.get("testset_id"), experiment = (UUID) r.get("experiment_id"), previousRun = (UUID) r.get("previous_run_id");
            String experimentStatus = (String) r.get("experiment_status");
            List<StaleQuestion> stale = setId == null ? List.of() : j.query("SELECT id, question FROM test_questions WHERE testset_id = ? AND ? = ANY (quality_flags) ORDER BY seq",
                    (rs, n) -> new StaleQuestion(rs.getObject(1, UUID.class), rs.getString(2)), setId, ChangeImpactHandler.STALE);

            Verdict verdict = null;
            List<Flip> flips = new ArrayList<>();
            int failToPass = 0;
            if ("DONE".equals(experimentStatus) && previousRun != null) {
                UUID newRun = j.queryForObject("SELECT id FROM runs WHERE experiment_id = ?", UUID.class, experiment);
                Map<UUID, double[]> before = byStable(j, previousRun), after = byStable(j, newRun);
                List<UUID> common = new ArrayList<>(before.keySet());
                common.retainAll(after.keySet());
                common.sort(null);
                if (!common.isEmpty()) {
                    double[] a = new double[common.size()], b = new double[common.size()];
                    for (int i = 0; i < a.length; i++) {
                        a[i] = before.get(common.get(i))[0];
                        b[i] = after.get(common.get(i))[0];
                    }
                    var cmp = Comparisons.compare(Map.of("context_pass", a), Map.of("context_pass", b), previousRun + ":" + newRun);
                    var row = cmp.stream().filter(x -> x.metric().equals("context_pass")).findFirst().orElse(null);
                    if (row != null) verdict = new Verdict(row.a(), row.b(), row.delta(), row.ciLow(), row.ciHigh(), row.pAdjusted(), row.verdict(), common.size());
                    var names = questions(j, newRun);
                    for (int i = 0; i < a.length; i++) {
                        boolean wasPass = a[i] > 0.5, isPass = b[i] > 0.5;
                        if (wasPass && !isPass) flips.add(new Flip(common.get(i), names.get(common.get(i))[0], names.get(common.get(i))[1]));
                        if (!wasPass && isPass) failToPass++;
                    }
                }
            }
            return new Report(version, (String) r.get("status"), (String) r.get("note"), setId, (Integer) r.get("testset_version"), experiment, experimentStatus,
                    (Integer) r.get("relocated"), (Integer) r.get("stale"), stale, verdict, flips, failToPass, diff);
        });
    }

    /** Per stable question id: the context_pass of the run. */
    private Map<UUID, double[]> byStable(org.springframework.jdbc.core.JdbcTemplate j, UUID runId) {
        Map<UUID, double[]> out = new LinkedHashMap<>();
        j.query("SELECT q.stable_id, (r.metrics->>'context_pass')::float8 AS pass FROM run_results r JOIN test_questions q ON q.id = r.question_id WHERE r.run_id = ? AND jsonb_exists(r.metrics, 'context_pass')",
                rs -> { out.put(rs.getObject(1, UUID.class), new double[] {rs.getDouble(2)}); }, runId);
        return out;
    }

    private Map<UUID, String[]> questions(org.springframework.jdbc.core.JdbcTemplate j, UUID runId) {
        Map<UUID, String[]> out = new LinkedHashMap<>();
        j.query("SELECT q.stable_id, q.question, r.failure_stage FROM run_results r JOIN test_questions q ON q.id = r.question_id WHERE r.run_id = ?",
                rs -> { out.put(rs.getObject(1, UUID.class), new String[] {rs.getString(2), rs.getString(3)}); }, runId);
        return out;
    }
}
