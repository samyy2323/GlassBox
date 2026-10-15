package dev.glassbox.autotune;

import static dev.glassbox.workspace.WorkspaceService.audit;
import static dev.glassbox.workspace.WorkspaceService.require;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Principal;
import dev.glassbox.autotune.AutotuneModel.*;
import dev.glassbox.common.ApiException;
import dev.glassbox.embed.EmbeddingProviders;
import dev.glassbox.eval.MeasurableQuestions;
import dev.glassbox.index.IndexService;
import dev.glassbox.jobs.JobQueue;
import dev.glassbox.retrieval.HybridSql;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.WorkspaceDefaults;
import dev.glassbox.stats.Power;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Creating, reading, cancelling and promoting AutoTune searches (SPEC 9). The search itself runs in {@link AutotuneHandler}. */
@Service
public class AutotuneService {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final Tx tx;
    private final ObjectMapper json;
    private final JobQueue queue;
    private final IndexService indexes;
    private final EmbeddingProviders providers;
    private final WorkspaceDefaults defaults;
    private final int defaultSeed;

    public AutotuneService(Tx tx, ObjectMapper json, JobQueue queue, IndexService indexes, EmbeddingProviders providers, WorkspaceDefaults defaults,
                           @Value("${glassbox.eval.seed}") int defaultSeed) {
        this.tx = tx;
        this.json = json;
        this.queue = queue;
        this.indexes = indexes;
        this.providers = providers;
        this.defaults = defaults;
        this.defaultSeed = defaultSeed;
    }

    // ------------------------------------------------------------------ options

    Options options(Request r) {
        Objective objective;
        try {
            objective = r.objective() == null || r.objective().isBlank() ? Objective.BEST_QUALITY : Objective.valueOf(r.objective().strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("objective must be one of " + List.of(Objective.values()));
        }
        double floor = in(r.qualityFloor(), 0.8, 0.0, 1.0, "qualityFloor");
        return new Options(objective, floor, in(r.latencyCapMs(), 1500, 1, 600_000, "latencyCapMs"), Boolean.TRUE.equals(r.fullStage()),
                in(r.stage1Candidates(), 18, 1, 60, "stage1Candidates"), in(r.stage2Candidates(), 24, 1, 60, "stage2Candidates"), in(r.finalists(), 5, 1, 8, "finalists"),
                in(r.fullQuestions(), 20, 1, 200, "fullQuestions"), in(r.capMinutes(), 60, 1, 600, "capMinutes"), in(r.capLlmCalls(), 600, 1, 20_000, "capLlmCalls"), in(r.capSearches(), 20_000, 1, 1_000_000, "capSearches"),
                r.seed() != null ? r.seed() : defaultSeed);
    }

    private static int in(Integer v, int def, int min, int max, String field) {
        int x = v == null ? def : v;
        if (x < min || x > max) throw ApiException.badRequest(field + " must be between " + min + " and " + max);
        return x;
    }

    private static double in(Double v, double def, double min, double max, String field) {
        double x = v == null ? def : v;
        if (Double.isNaN(x) || x < min || x > max) throw ApiException.badRequest(field + " must be between " + min + " and " + max);
        return x;
    }

    /** Models a search may use: those with an index and a provider. */
    List<String> models() {
        return HybridSql.MODEL_DIMS.keySet().stream().filter(providers::has).sorted().toList();
    }

    // ------------------------------------------------------------------ estimate and create

    public Estimate estimate(Principal p, UUID ws, Request r) {
        Options o = options(r);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            testsetMustBeFinal(j, r.testsetId());
            Split s = split(j, r.testsetId(), o);
            List<SearchSpace.Candidate> s1 = SearchSpace.stage1(o.seed(), o.stage1Candidates(), models(), providers.defaultModel());
            long calls = retrievalCalls(s1.size(), s.tuningRetrieval()) + retrievalCalls(o.stage2Candidates(), s.tuningRetrieval()) + (long) (o.finalists() + 1) * s.heldOutRetrieval();
            long llm = o.fullStage() ? (long) (o.finalists() + 1) * Math.min(o.fullQuestions(), s.tuning()) * 2 * 5 : 0;
            List<String> notes = new ArrayList<>();
            notes.add("The test set is split " + s.tuning() + " to tune on and " + s.heldOut() + " held out. Choices are made on the tuning questions only; the headline numbers come from the held-out ones.");
            if (s.heldOut() < 30) {
                double hw = Power.minDetectableForRate(s.heldOut(), 0.8) * 100;
                notes.add("Only " + s.heldOut() + " questions are held out, so the held-out result can only separate configurations that differ by about " + Math.round(hw)
                        + " points or more. Add questions for a sharper answer.");
            }
            Set<IndexService.Need> needs = new LinkedHashSet<>();
            for (SearchSpace.Candidate c : s1) needs.addAll(indexes.needs(List.of(c.config())));
            int missing = 0;
            for (IndexService.Need need : needs) if (!indexes.plan(ws, need).missing().isEmpty()) missing++;
            if (missing > 0) notes.add(missing + " of the " + needs.size() + " chunking and embedding combinations in stage 1 are not indexed yet; they are built first, one document at a time.");
            notes.add(o.fullStage() ? "Stage 3 and the held-out check write and judge answers: about " + llm + " model calls. On a small local model that takes a long time."
                    : "Answers are not generated: quality is context recall, measured without any model call. Turn on the full stage to include correctness and faithfulness.");
            notes.add("The search stops after " + o.capMinutes() + " minutes, " + o.capSearches() + " searches or " + o.capLlmCalls() + " model calls, whichever comes first, and keeps the best result so far.");
            return new Estimate(s.tuning(), s.heldOut(), s1.size(), o.stage2Candidates(), o.finalists(), calls, llm, notes);
        });
    }

    /** Question counts per part, for the estimate. */
    record Split(int tuning, int heldOut, int tuningRetrieval, int heldOutRetrieval) {}

    Split split(JdbcTemplate j, UUID testsetId, Options o) {
        MeasurableQuestions.Loaded all = MeasurableQuestions.load(j, json, testsetId, true, null);
        Splits.Result sp = Splits.split(o.seed(), all.questions().stream().map(q -> new Splits.Item(q.id(), q.qtype())).toList());
        int tr = 0, hr = 0;
        for (var q : all.questions()) {
            if (q.spans().isEmpty()) continue;
            if (sp.isHeldOut(q.id())) hr++; else tr++;
        }
        return new Split(sp.tuning().size(), sp.heldOut().size(), tr, hr);
    }

    /** Scoring calls of a halving run, counting only new questions per round. */
    static long retrievalCalls(int candidates, int questions) {
        if (candidates == 0 || questions == 0) return 0;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < candidates; i++) ids.add("c" + i);
        var run = SuccessiveHalving.run(ids, questions, 4, 1, (id, size) -> 0.0, Comparator.naturalOrder());
        long calls = 0;
        int previous = 0;
        for (var r : run.rounds()) {
            calls += (long) r.entered().size() * (r.sample() - previous);
            previous = r.sample();
        }
        return calls;
    }

    public View create(Principal p, UUID ws, Request r) {
        String name = r.name() == null ? "" : r.name().strip();
        if (name.isEmpty() || name.length() > 120) throw ApiException.badRequest("An AutoTune run needs a name of 1 to 120 characters");
        Options o = options(r);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            testsetMustBeFinal(j, r.testsetId());
            Split s = split(j, r.testsetId(), o);
            if (s.tuningRetrieval() < 1 || s.heldOutRetrieval() < 1) {
                throw ApiException.conflict("AutoTune needs answerable questions on both sides of the split: " + s.tuningRetrieval() + " to tune on and " + s.heldOutRetrieval()
                        + " held out. Add questions to the test set.");
            }
            String optionsJson = write(o);
            String splitJson = write(Map.of("tuning", s.tuning(), "heldOut", s.heldOut(), "tuningAnswerable", s.tuningRetrieval(), "heldOutAnswerable", s.heldOutRetrieval(), "seed", o.seed()));
            UUID id = j.queryForObject("INSERT INTO autotunes (workspace_id, testset_id, name, objective, options, split, created_by) VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) RETURNING id",
                    UUID.class, ws, r.testsetId(), name, o.objective().name(), optionsJson, splitJson, p.userId());
            queue.enqueue(j, ws, AutotuneHandler.TYPE, json.createObjectNode().put("autotuneId", id.toString()), "autotune-" + id);
            audit(j, ws, p.actor(), "autotune.create", "autotune:" + id);
            return view(j, id);
        });
    }

    private void testsetMustBeFinal(JdbcTemplate j, UUID testsetId) {
        var rows = j.queryForList("SELECT status FROM testsets WHERE id = ?", String.class, testsetId);
        if (rows.isEmpty()) throw ApiException.notFound("No such test set");
        if (!rows.get(0).equals("FINAL")) throw ApiException.conflict("Only a final test set can be tuned against. Finalize it first.");
    }

    // ------------------------------------------------------------------ reading, cancelling, promoting

    public List<Summary> list(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            return j.query("SELECT a.id, a.name, t.name AS testset, a.objective, a.status, a.stage, a.created_at, a.finished_at, a.result->'headline'->>'verdict' AS verdict "
                    + "FROM autotunes a JOIN testsets t ON t.id = a.testset_id ORDER BY a.created_at DESC, a.id DESC LIMIT 100",
                    (rs, n) -> new Summary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("testset"), rs.getString("objective"), rs.getString("status"),
                            rs.getString("stage"), rs.getObject("created_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class), rs.getString("verdict")));
        });
    }

    public View get(Principal p, UUID ws, UUID id) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            return view(j, id);
        });
    }

    public void cancel(Principal p, UUID ws, UUID id) {
        tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            var rows = j.queryForList("SELECT status FROM autotunes WHERE id = ? FOR UPDATE", String.class, id);
            if (rows.isEmpty()) throw ApiException.notFound("No such AutoTune run");
            if (!rows.get(0).equals("QUEUED") && !rows.get(0).equals("RUNNING")) throw ApiException.conflict("This AutoTune run has already " + rows.get(0).toLowerCase());
            j.update("UPDATE autotunes SET status = 'CANCELLED', finished_at = now() WHERE id = ?", id);
            audit(j, ws, p.actor(), "autotune.cancel", "autotune:" + id);
            return null;
        });
    }

    /** Saves the winning configuration as the workspace default: chat and search start from it. OWNER only. */
    public PipelineConfig promote(Principal p, UUID ws, UUID id) {
        PipelineConfig config = tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.OWNER);
            var rows = j.queryForList("SELECT status, result::text AS result FROM autotunes WHERE id = ?", id);
            if (rows.isEmpty()) throw ApiException.notFound("No such AutoTune run");
            if (!"DONE".equals(rows.get(0).get("status")) || rows.get(0).get("result") == null) throw ApiException.conflict("Only a finished AutoTune run can be promoted");
            try {
                JsonNode winner = json.readTree((String) rows.get(0).get("result")).path("headline").path("winnerConfig");
                if (winner.isMissingNode() || winner.isNull()) throw ApiException.conflict("This run did not produce a winner");
                audit(j, ws, p.actor(), "autotune.promote", "autotune:" + id);
                return json.treeToValue(winner, PipelineConfig.class);
            } catch (ApiException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        defaults.promote(ws, p.userId(), config);
        return config;
    }

    private View view(JdbcTemplate j, UUID id) {
        var rows = j.queryForList("SELECT a.*, a.options::text AS options_text, a.split::text AS split_text, a.spent::text AS spent_text, a.result::text AS result_text, "
                + "t.name AS testset_name FROM autotunes a JOIN testsets t ON t.id = a.testset_id WHERE a.id = ?", id);
        if (rows.isEmpty()) throw ApiException.notFound("No such AutoTune run");
        var r = rows.get(0);
        Map<String, Integer> candidates = new LinkedHashMap<>();
        j.query("SELECT stage, count(*) AS n FROM autotune_candidates WHERE autotune_id = ? GROUP BY stage ORDER BY stage", rs -> { candidates.put(rs.getString("stage"), rs.getInt("n")); }, id);
        try {
            return new View(id, (UUID) r.get("testset_id"), (String) r.get("testset_name"), (String) r.get("name"), (String) r.get("objective"), (String) r.get("status"),
                    (String) r.get("stage"), json.readValue((String) r.get("options_text"), Options.class), json.readValue((String) r.get("split_text"), MAP),
                    json.readValue((String) r.get("spent_text"), MAP), (String) r.get("stopped_by"), (String) r.get("error"), (UUID) r.get("experiment_id"),
                    r.get("result_text") == null ? null : json.readTree((String) r.get("result_text")), candidates, r.get("created_at") == null ? null : ((java.sql.Timestamp) r.get("created_at")).toInstant().atOffset(java.time.ZoneOffset.UTC),
                    r.get("finished_at") == null ? null : ((java.sql.Timestamp) r.get("finished_at")).toInstant().atOffset(java.time.ZoneOffset.UTC));
        } catch (Exception e) {
            throw new IllegalStateException("stored AutoTune data is unreadable", e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
