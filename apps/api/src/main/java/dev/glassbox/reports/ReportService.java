package dev.glassbox.reports;

import static dev.glassbox.workspace.WorkspaceService.audit;
import static dev.glassbox.workspace.WorkspaceService.require;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Hashing;
import dev.glassbox.auth.Principal;
import dev.glassbox.autotune.AutotuneModel;
import dev.glassbox.autotune.AutotuneService;
import dev.glassbox.common.ApiException;
import dev.glassbox.eval.ExperimentModel.ExperimentView;
import dev.glassbox.eval.ExperimentService;
import dev.glassbox.reports.ReportBuilder.TestsetFacts;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reports (SPEC 11, T2.10): a frozen snapshot of a run, exported as a PDF or shared by a read-only link. A link holds a random 256-bit token that is shown once
 * (only its hash is stored), expires, can be revoked, and opens one report through one endpoint that reads nothing but that report's snapshot.
 */
@Service
public class ReportService {
    public record Meta(UUID id, String kind, UUID refId, String title, boolean redacted, OffsetDateTime createdAt, int activeLinks) {}

    public record Link(UUID id, OffsetDateTime expiresAt, OffsetDateTime revokedAt, OffsetDateTime createdAt, boolean active) {}

    /** {@code token} is in this response only; it cannot be read back. */
    public record Created(UUID id, String token, OffsetDateTime expiresAt, String path) {}

    public record Shared(String title, JsonNode snapshot, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

    static final int TOKEN_BYTES = 32;
    /** 32 bytes in unpadded base64url. */
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    static final int MAX_DAYS = 90;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Tx tx;
    private final ObjectMapper json;
    private final ReportBuilder builder;
    private final ReportPdf pdf;
    private final ExperimentService experiments;
    private final AutotuneService autotunes;

    public ReportService(Tx tx, ObjectMapper json, ReportBuilder builder, ReportPdf pdf, ExperimentService experiments, AutotuneService autotunes) {
        this.tx = tx;
        this.json = json;
        this.builder = builder;
        this.pdf = pdf;
        this.experiments = experiments;
        this.autotunes = autotunes;
    }

    // ------------------------------------------------------------------ making and reading a report

    public Meta create(Principal p, UUID ws, String kind, UUID refId, boolean redact) {
        if (!"EXPERIMENT".equals(kind) && !"AUTOTUNE".equals(kind)) throw ApiException.badRequest("kind must be EXPERIMENT or AUTOTUNE");
        if (refId == null) throw ApiException.badRequest("Say which run the report is about");
        tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            return null;
        });
        OffsetDateTime now = OffsetDateTime.now();
        JsonNode snapshot;
        if (kind.equals("EXPERIMENT")) {
            ExperimentView e = experiments.get(p, ws, refId);
            if (!List.of("DONE", "ABORTED").contains(e.status())) throw ApiException.conflict("A report is made from a finished run; this one is " + e.status().toLowerCase() + ".");
            TestsetFacts facts = tx.workspace(ws, p.userId(), j -> facts(j, e.testsetId(), e.testsetName(), e.testsetVersion()));
            snapshot = builder.experiment(e, facts, tx.workspace(ws, p.userId(), j -> failures(j, e)), redact, now);
        } else {
            AutotuneModel.View a = autotunes.get(p, ws, refId);
            if (!"DONE".equals(a.status())) throw ApiException.conflict("A report is made from a finished run; this one is " + a.status().toLowerCase() + ".");
            TestsetFacts facts = tx.workspace(ws, p.userId(), j -> facts(j, a.testsetId(), a.testsetName(), 0));
            snapshot = builder.autotune(a, facts, redact, now);
        }
        String title = snapshot.path("title").asText("Report");
        return tx.workspace(ws, p.userId(), j -> {
            UUID id = j.queryForObject("INSERT INTO reports (workspace_id, kind, ref_id, title, snapshot, redacted, created_by) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?) RETURNING id",
                    UUID.class, ws, kind, refId, title, snapshot.toString(), redact, p.userId());
            audit(j, ws, p.actor(), "report.create", "report:" + id);
            return meta(j, id);
        });
    }

    public List<Meta> list(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            return j.query("SELECT id FROM reports ORDER BY created_at DESC LIMIT 100", (rs, n) -> rs.getObject(1, UUID.class)).stream().map(id -> meta(j, id)).toList();
        });
    }

    public JsonNode snapshot(Principal p, UUID ws, UUID id) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            return read(j, id);
        });
    }

    public byte[] pdf(Principal p, UUID ws, UUID id) {
        return pdf.render(snapshot(p, ws, id));
    }

    private JsonNode read(JdbcTemplate j, UUID id) {
        var rows = j.queryForList("SELECT snapshot::text AS s FROM reports WHERE id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("No such report");
        try {
            return json.readTree((String) rows.get(0).get("s"));
        } catch (Exception e) {
            throw new IllegalStateException("stored report is unreadable", e);
        }
    }

    private Meta meta(JdbcTemplate j, UUID id) {
        var rows = j.query("SELECT r.id, r.kind, r.ref_id, r.title, r.redacted, r.created_at, (SELECT count(*) FROM report_links l WHERE l.report_id = r.id AND l.revoked_at IS NULL AND l.expires_at > now()) AS links "
                + "FROM reports r WHERE r.id = ?", (rs, n) -> new Meta(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("ref_id", UUID.class), rs.getString("title"),
                rs.getBoolean("redacted"), rs.getObject("created_at", OffsetDateTime.class), rs.getInt("links")), id);
        if (rows.isEmpty()) throw ApiException.notFound("No such report");
        return rows.get(0);
    }

    // ------------------------------------------------------------------ links

    public Created createLink(Principal p, UUID ws, UUID reportId, Integer days) {
        int d = days == null ? 7 : days;
        if (d < 1 || d > MAX_DAYS) throw ApiException.badRequest("A link can last between 1 and " + MAX_DAYS + " days");
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            read(j, reportId); // 404 for another workspace's report
            OffsetDateTime expires = j.queryForObject("INSERT INTO report_links (workspace_id, report_id, token_hash, expires_at, created_by) VALUES (?, ?, ?, now() + make_interval(days => ?), ?) RETURNING expires_at",
                    OffsetDateTime.class, ws, reportId, Hashing.sha256Hex(token), d, p.userId());
            UUID linkId = j.queryForObject("SELECT id FROM report_links WHERE token_hash = ?", UUID.class, Hashing.sha256Hex(token));
            audit(j, ws, p.actor(), "report.link.create", "report:" + reportId + " link:" + linkId); // never the token
            return new Created(linkId, token, expires, "/r/" + token);
        });
    }

    public List<Link> links(Principal p, UUID ws, UUID reportId) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            read(j, reportId);
            return j.query("SELECT id, expires_at, revoked_at, created_at, (revoked_at IS NULL AND expires_at > now()) AS active FROM report_links WHERE report_id = ? ORDER BY created_at DESC",
                    (rs, n) -> new Link(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class), rs.getBoolean(5)), reportId);
        });
    }

    public void revoke(Principal p, UUID ws, UUID reportId, UUID linkId) {
        tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            int n = j.update("UPDATE report_links SET revoked_at = now() WHERE id = ? AND report_id = ? AND revoked_at IS NULL", linkId, reportId);
            if (n == 0) throw ApiException.notFound("No such active link");
            audit(j, ws, p.actor(), "report.link.revoke", "report:" + reportId + " link:" + linkId);
            return null;
        });
    }

    public void delete(Principal p, UUID ws, UUID reportId) {
        tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            if (j.update("DELETE FROM reports WHERE id = ?", reportId) == 0) throw ApiException.notFound("No such report");
            audit(j, ws, p.actor(), "report.delete", "report:" + reportId);
            return null;
        });
    }

    // ------------------------------------------------------------------ the public side

    /**
     * What a share link shows. No tenant context exists here: the only way in is the database function {@code public_report}, which returns one snapshot for a
     * valid, unexpired, unrevoked token hash. A malformed, unknown, expired or revoked token all look the same, and the token is never logged.
     */
    public Optional<Shared> shared(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) return Optional.empty();
        return tx.anonymous(j -> j.query("SELECT title, snapshot::text AS s, created_at, expires_at FROM public_report(?)", (rs, n) -> {
            try {
                return new Shared(rs.getString("title"), json.readTree(rs.getString("s")), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class));
            } catch (Exception e) {
                throw new IllegalStateException("stored report is unreadable", e);
            }
        }, Hashing.sha256Hex(token)).stream().findFirst());
    }

    public byte[] sharedPdf(String token) {
        return shared(token).map(s -> pdf.render(s.snapshot())).orElseThrow(() -> ApiException.notFound("This link is not valid"));
    }

    // ------------------------------------------------------------------ facts for the snapshot

    private TestsetFacts facts(JdbcTemplate j, UUID testsetId, String name, int version) {
        var s = j.queryForMap("SELECT count(*) AS total, count(*) FILTER (WHERE review_status = 'ACCEPTED') AS accepted, count(*) FILTER (WHERE review_status = 'EDITED') AS edited, "
                + "count(*) FILTER (WHERE review_status = 'REJECTED') AS rejected, count(*) FILTER (WHERE origin = 'FEEDBACK' AND review_status <> 'REJECTED') AS fb FROM test_questions WHERE testset_id = ?", testsetId);
        int accepted = ((Number) s.get("accepted")).intValue(), edited = ((Number) s.get("edited")).intValue(), rejected = ((Number) s.get("rejected")).intValue();
        Map<String, Integer> byType = new LinkedHashMap<>();
        j.query("SELECT qtype, count(*) FROM test_questions WHERE testset_id = ? AND review_status IN ('ACCEPTED', 'EDITED') GROUP BY qtype ORDER BY qtype", rs -> { byType.put(rs.getString(1), rs.getInt(2)); }, testsetId);
        int reviewed = accepted + edited + rejected;
        int v = version > 0 ? version : j.queryForObject("SELECT version FROM testsets WHERE id = ?", Integer.class, testsetId);
        return new TestsetFacts(name, v, ((Number) s.get("total")).intValue(), accepted, edited, rejected, reviewed == 0 ? null : (double) (accepted + edited) / reviewed, byType, ((Number) s.get("fb")).intValue());
    }

    private ReportBuilder.Failures failures(JdbcTemplate j, ExperimentView e) {
        List<String[]> stages = new java.util.ArrayList<>(), questions = new java.util.ArrayList<>();
        for (var run : e.runs()) {
            j.query("SELECT q.question, r.failure_stage FROM run_results r JOIN test_questions q ON q.id = r.question_id WHERE r.run_id = ? AND r.failure_stage IS NOT NULL ORDER BY q.seq",
                    rs -> {
                        stages.add(new String[] {run.label(), rs.getString(2)});
                        if (questions.size() < 200) questions.add(new String[] {run.label(), rs.getString(1), rs.getString(2)});
                    }, run.id());
        }
        // every run appears, so a run with no failures reads "no failures" and not "missing"
        var failures = ReportBuilder.failures(stages, questions);
        for (var run : e.runs()) failures.byStage().computeIfAbsent(run.label(), k -> new LinkedHashMap<>());
        return failures;
    }
}
