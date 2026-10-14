package dev.glassbox.eval;

import static dev.glassbox.workspace.WorkspaceService.audit;
import static dev.glassbox.workspace.WorkspaceService.require;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.eval.ExperimentModel.*;
import dev.glassbox.jobs.JobQueue;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalParams;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Experiments: one FINAL test set version plus a list of configurations, each of which becomes a run (SPEC 7). The first
 * configuration is the baseline everything else is compared against. Running happens in {@link RunExperimentHandler}.
 */
@Service
public class ExperimentService {
    public static final int MAX_CONFIGS = 8;
    private static final TypeReference<Map<String, Double>> METRICS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private final Tx tx;
    private final ObjectMapper json;
    private final JobQueue queue;
    private final String embeddingModel;

    public ExperimentService(Tx tx, ObjectMapper json, JobQueue queue, @Value("${glassbox.embed.default-model}") String embeddingModel) {
        this.tx = tx;
        this.json = json;
        this.queue = queue;
        this.embeddingModel = embeddingModel;
    }

    // ------------------------------------------------------------------ configurations

    /** A user's configuration to a real PipelineConfig, with every value checked: the search runs with whatever this returns. */
    PipelineConfig toConfig(ConfigInput in) {
        RetrievalParams.Mode mode = parse(RetrievalParams.Mode.class, in.mode(), RetrievalParams.Mode.HYBRID, "mode");
        RetrievalParams.KeywordMatch keyword = parse(RetrievalParams.KeywordMatch.class, in.keywordMatch(), RetrievalParams.KeywordMatch.ANY, "keywordMatch");
        Reranker.Type reranker = parse(Reranker.Type.class, in.reranker(), Reranker.Type.NONE, "reranker");
        int kFused = within(in.kFused(), 50, 1, 200, "kFused");
        int rrfK = within(in.rrfK(), 60, 1, 1000, "rrfK");
        double wVec = within(in.wVec(), 1.0, 0, 10, "wVec"), wKw = within(in.wKw(), 1.0, 0, 10, "wKw");
        if (mode == RetrievalParams.Mode.HYBRID && wVec + wKw <= 0) throw ApiException.badRequest("A hybrid search needs a positive weight for at least one side");
        int topN = within(in.topN(), 5, 1, 50, "topN");
        int budget = within(in.tokenBudget(), 2000, 100, 32000, "tokenBudget");
        int pool = Math.max(50, kFused);
        RetrievalParams params = new RetrievalParams(mode, embeddingModel, pool, pool, rrfK, wVec, wKw, kFused, 100, keyword);
        return new PipelineConfig(params, reranker, Math.max(50, topN), topN, budget);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String field) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(field + " must be one of " + List.of(type.getEnumConstants()));
        }
    }

    private static int within(Integer v, int fallback, int min, int max, String field) {
        int x = v == null ? fallback : v;
        if (x < min || x > max) throw ApiException.badRequest(field + " must be between " + min + " and " + max);
        return x;
    }

    private static double within(Double v, double fallback, double min, double max, String field) {
        double x = v == null ? fallback : v;
        if (Double.isNaN(x) || x < min || x > max) throw ApiException.badRequest(field + " must be between " + min + " and " + max);
        return x;
    }

    private List<PipelineConfig> validate(List<ConfigInput> inputs) {
        if (inputs == null || inputs.isEmpty()) throw ApiException.badRequest("Choose at least one configuration to measure");
        if (inputs.size() > MAX_CONFIGS) throw ApiException.badRequest("At most " + MAX_CONFIGS + " configurations per experiment");
        Set<String> names = new HashSet<>();
        List<PipelineConfig> out = new ArrayList<>();
        for (ConfigInput in : inputs) {
            String name = in.name() == null ? "" : in.name().strip();
            if (name.isEmpty() || name.length() > 80) throw ApiException.badRequest("Every configuration needs a name of 1 to 80 characters");
            if (!names.add(name)) throw ApiException.badRequest("Two configurations are called \"" + name + "\"");
            out.add(toConfig(in));
        }
        return out;
    }

    private String hash(PipelineConfig config) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(config).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireRetrievalMode(String mode) {
        if (mode != null && !mode.isBlank() && !mode.equalsIgnoreCase("RETRIEVAL")) {
            throw ApiException.badRequest("Only retrieval experiments are available so far");
        }
    }

    // ------------------------------------------------------------------ estimate and create

    public Estimate estimate(Principal p, UUID ws, UUID testsetId, List<ConfigInput> configs, String mode) {
        requireRetrievalMode(mode);
        List<PipelineConfig> validated = validate(configs);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            String status = testsetStatus(j, testsetId);
            MeasurableQuestions.Loaded loaded = MeasurableQuestions.load(j, json, testsetId);
            List<String> notes = new ArrayList<>();
            if (!status.equals("FINAL")) notes.add("This test set is " + status.toLowerCase() + ". Only a final version can be measured.");
            if (loaded.stale() > 0) notes.add(loaded.stale() + " question(s) are left out because their document changed since the test set was written.");
            if (loaded.withoutEvidence() > 0) notes.add(loaded.withoutEvidence() + " question(s) have no gold evidence (unanswerable) and are not part of retrieval metrics.");
            int calls = loaded.questions().size() * validated.size();
            notes.add("Retrieval only: no text is generated, so this costs no model tokens and runs in seconds.");
            return new Estimate(loaded.questions().size(), loaded.stale(), validated.size(), calls, 0, 0.0, notes);
        });
    }

    public ExperimentView create(Principal p, UUID ws, UUID testsetId, String name, String mode, List<ConfigInput> configs) {
        requireRetrievalMode(mode);
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isEmpty() || cleanName.length() > 120) throw ApiException.badRequest("An experiment needs a name of 1 to 120 characters");
        List<PipelineConfig> validated = validate(configs);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            if (!testsetStatus(j, testsetId).equals("FINAL")) throw ApiException.conflict("Only a final test set can be measured. Finalize it first.");
            MeasurableQuestions.Loaded loaded = MeasurableQuestions.load(j, json, testsetId);
            if (loaded.questions().isEmpty()) throw ApiException.conflict("This test set has no accepted question with intact evidence to measure");

            Map<String, Object> estimate = new LinkedHashMap<>();
            estimate.put("questions", loaded.questions().size());
            estimate.put("retrievalCalls", loaded.questions().size() * validated.size());
            estimate.put("llmCalls", 0);
            estimate.put("usd", 0.0);
            UUID id = j.queryForObject("INSERT INTO experiments (workspace_id, testset_id, name, mode, question_count, stale_count, estimated_cost, created_by) "
                            + "VALUES (?, ?, ?, 'RETRIEVAL', ?, ?, ?::jsonb, ?) RETURNING id", UUID.class,
                    ws, testsetId, cleanName, loaded.questions().size(), loaded.stale(), write(estimate), p.userId());
            for (int i = 0; i < validated.size(); i++) {
                UUID configId = j.queryForObject("INSERT INTO pipeline_configs (workspace_id, config, config_hash) VALUES (?, ?::jsonb, ?) "
                                + "ON CONFLICT (workspace_id, config_hash) DO UPDATE SET config = EXCLUDED.config RETURNING id", UUID.class,
                        ws, write(validated.get(i)), hash(validated.get(i)));
                j.update("INSERT INTO runs (workspace_id, experiment_id, config_id, label, ordinal) VALUES (?, ?, ?, ?, ?)",
                        ws, id, configId, configs.get(i).name().strip(), i);
            }
            queue.enqueue(j, ws, RunExperimentHandler.TYPE, json.createObjectNode().put("experimentId", id.toString()), "experiment-" + id);
            audit(j, ws, p.actor(), "experiment.create", "experiment:" + id);
            return view(j, id, false);
        });
    }

    // ------------------------------------------------------------------ reading

    public List<ExperimentView> list(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            List<UUID> ids = j.queryForList("SELECT id FROM experiments ORDER BY created_at DESC, id DESC LIMIT 100", UUID.class);
            List<ExperimentView> out = new ArrayList<>();
            for (UUID id : ids) out.add(view(j, id, false));
            return out;
        });
    }

    public ExperimentView get(Principal p, UUID ws, UUID id) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            return view(j, id, true);
        });
    }

    public List<ResultView> results(Principal p, UUID ws, UUID experimentId, UUID runId) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            if (j.queryForList("SELECT 1 FROM runs WHERE id = ? AND experiment_id = ?", runId, experimentId).isEmpty()) throw ApiException.notFound("No such run");
            return j.query("SELECT r.question_id, q.question, q.qtype, r.metrics::text AS metrics, r.latency_ms, r.failure_stage, r.retrieved::text AS retrieved, "
                            + "r.final_context::text AS final_context, r.trace_id FROM run_results r JOIN test_questions q ON q.id = r.question_id "
                            + "WHERE r.run_id = ? ORDER BY q.seq", (rs, n) -> new ResultView(rs.getObject("question_id", UUID.class), rs.getString("question"),
                            rs.getString("qtype"), read(rs.getString("metrics"), METRICS), (Integer) rs.getObject("latency_ms"), rs.getString("failure_stage"),
                            readTree(rs.getString("retrieved")), readTree(rs.getString("final_context")), rs.getObject("trace_id", UUID.class)), runId);
        });
    }

    public void cancel(Principal p, UUID ws, UUID id) {
        tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            var rows = j.queryForList("SELECT status FROM experiments WHERE id = ? FOR UPDATE", String.class, id);
            if (rows.isEmpty()) throw ApiException.notFound("No such experiment");
            if (!rows.get(0).equals("QUEUED") && !rows.get(0).equals("RUNNING")) throw ApiException.conflict("This experiment has already " + rows.get(0).toLowerCase());
            j.update("UPDATE experiments SET status = 'CANCELLED', finished_at = now() WHERE id = ?", id);
            j.update("UPDATE runs SET status = 'CANCELLED' WHERE experiment_id = ? AND status <> 'DONE'", id);
            audit(j, ws, p.actor(), "experiment.cancel", "experiment:" + id);
            return null;
        });
    }

    /** Two runs of one test set compared on the questions both measured. */
    public ComparisonView compare(Principal p, UUID ws, UUID runA, UUID runB) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            var a = runInfo(j, runA);
            var b = runInfo(j, runB);
            if (!a.testsetId().equals(b.testsetId())) throw ApiException.conflict("These runs measured different test sets, so they cannot be compared question by question");
            if (!a.status().equals("DONE") || !b.status().equals("DONE")) throw ApiException.conflict("Both runs must be finished before they are compared");
            Map<UUID, Map<String, Double>> va = scores(j, runA), vb = scores(j, runB);
            List<UUID> common = new ArrayList<>(va.keySet());
            common.sort(null);
            common.retainAll(vb.keySet());
            Aligned al = align(va, vb, common);
            List<Comparisons.Row> rows = Comparisons.compare(al.a(), al.b(), runA + ":" + runB);

            List<Flip> passToFail = new ArrayList<>(), failToPass = new ArrayList<>();
            Map<UUID, String[]> text = questionText(j, common);
            for (UUID q : common) {
                double x = va.get(q).get(RetrievalMetrics.CONTEXT_PASS), y = vb.get(q).get(RetrievalMetrics.CONTEXT_PASS);
                if (x == y) continue;
                Flip flip = new Flip(q, text.get(q)[0], text.get(q)[1], va.get(q).get(RetrievalMetrics.CONTEXT_RECALL), vb.get(q).get(RetrievalMetrics.CONTEXT_RECALL));
                if (x > y) passToFail.add(flip);
                else failToPass.add(flip);
            }
            return new ComparisonView(runA, runB, a.label(), b.label(), common.size(), rows, passToFail, failToPass);
        });
    }

    // ------------------------------------------------------------------ shared with the runner

    record RunInfo(UUID id, UUID experimentId, UUID testsetId, String label, String status) {}

    private RunInfo runInfo(JdbcTemplate j, UUID runId) {
        var rows = j.query("SELECT r.id, r.experiment_id, e.testset_id, r.label, r.status FROM runs r JOIN experiments e ON e.id = r.experiment_id WHERE r.id = ?",
                (rs, n) -> new RunInfo(rs.getObject("id", UUID.class), rs.getObject("experiment_id", UUID.class), rs.getObject("testset_id", UUID.class),
                        rs.getString("label"), rs.getString("status")), runId);
        if (rows.isEmpty()) throw ApiException.notFound("No such run");
        return rows.get(0);
    }

    /** Per-question scores of a run, by question id. */
    Map<UUID, Map<String, Double>> scores(JdbcTemplate j, UUID runId) {
        Map<UUID, Map<String, Double>> out = new LinkedHashMap<>();
        j.query("SELECT question_id, metrics::text AS metrics FROM run_results WHERE run_id = ?",
                rs -> { out.put(rs.getObject("question_id", UUID.class), read(rs.getString("metrics"), METRICS)); }, runId);
        return out;
    }

    record Aligned(Map<String, double[]> a, Map<String, double[]> b) {}

    static Aligned align(Map<UUID, Map<String, Double>> a, Map<UUID, Map<String, Double>> b, List<UUID> questions) {
        Map<String, double[]> x = new LinkedHashMap<>(), y = new LinkedHashMap<>();
        if (questions.isEmpty()) return new Aligned(x, y);
        for (String metric : RetrievalMetrics.ALL) {
            double[] va = new double[questions.size()], vb = new double[questions.size()];
            boolean present = true;
            for (int i = 0; i < questions.size(); i++) {
                Double da = a.get(questions.get(i)).get(metric), db = b.get(questions.get(i)).get(metric);
                if (da == null || db == null) {
                    present = false;
                    break;
                }
                va[i] = da;
                vb[i] = db;
            }
            if (present) {
                x.put(metric, va);
                y.put(metric, vb);
            }
        }
        return new Aligned(x, y);
    }

    private Map<UUID, String[]> questionText(JdbcTemplate j, List<UUID> ids) {
        Map<UUID, String[]> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        j.query("SELECT id, question, qtype FROM test_questions WHERE id = ANY (?)", ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                rs -> { out.put(rs.getObject("id", UUID.class), new String[] {rs.getString("question"), rs.getString("qtype")}); });
        return out;
    }

    private String testsetStatus(JdbcTemplate j, UUID testsetId) {
        var rows = j.queryForList("SELECT status FROM testsets WHERE id = ?", String.class, testsetId);
        if (rows.isEmpty()) throw ApiException.notFound("No such test set");
        return rows.get(0);
    }

    private ExperimentView view(JdbcTemplate j, UUID id, boolean withComparisons) {
        var rows = j.query("SELECT e.id, e.testset_id, t.name AS testset_name, t.version AS testset_version, e.name, e.status, e.kind, e.mode, e.question_count, "
                        + "e.stale_count, e.estimated_cost::text AS estimated, e.actual_cost::text AS actual, e.error, e.created_at, e.finished_at "
                        + "FROM experiments e JOIN testsets t ON t.id = e.testset_id WHERE e.id = ?",
                (rs, n) -> new Object[] {rs.getObject("testset_id", UUID.class), rs.getString("testset_name"), rs.getInt("testset_version"), rs.getString("name"),
                        rs.getString("status"), rs.getString("kind"), rs.getString("mode"), rs.getInt("question_count"), rs.getInt("stale_count"),
                        read(rs.getString("estimated"), OBJECT_MAP), read(rs.getString("actual"), OBJECT_MAP), rs.getString("error"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class)}, id);
        if (rows.isEmpty()) throw ApiException.notFound("No such experiment");
        Object[] r = rows.get(0);

        List<RunView> runs = new ArrayList<>();
        var runRows = j.queryForList("SELECT r.id, r.label, r.ordinal, r.status, r.samples_done, c.config::text AS config FROM runs r JOIN pipeline_configs c ON c.id = r.config_id "
                + "WHERE r.experiment_id = ? ORDER BY r.ordinal", id);
        for (var row : runRows) {
            UUID runId = (UUID) row.get("id");
            List<MetricView> metrics = j.query("SELECT metric, slice, method, value, ci_low, ci_high, n FROM run_metrics WHERE run_id = ? ORDER BY metric, slice, method",
                    (rs, n) -> new MetricView(rs.getString("metric"), rs.getString("slice"), rs.getString("method"), finite(rs.getObject("value")), finite(rs.getObject("ci_low")),
                            finite(rs.getObject("ci_high")), rs.getInt("n")), runId);
            PipelineConfig config;
            try {
                config = json.readValue((String) row.get("config"), PipelineConfig.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("stored pipeline config is unreadable", e);
            }
            runs.add(new RunView(runId, (String) row.get("label"), (Integer) row.get("ordinal"), (String) row.get("status"), (Integer) row.get("samples_done"), config, metrics));
        }

        List<RunComparison> vsBaseline = withComparisons ? baselineComparisons(j, runs) : List.of();
        @SuppressWarnings("unchecked")
        ExperimentView view = new ExperimentView(id, (UUID) r[0], (String) r[1], (Integer) r[2], (String) r[3], (String) r[4], (String) r[5], (String) r[6], (Integer) r[7],
                (Integer) r[8], (Map<String, Object>) r[9], (Map<String, Object>) r[10], (String) r[11], (OffsetDateTime) r[12], (OffsetDateTime) r[13], runs, vsBaseline);
        return view;
    }

    /** Every finished run against the baseline (the first run), with one Holm correction over all of those tests together. */
    List<RunComparison> baselineComparisons(JdbcTemplate j, List<RunView> runs) {
        if (runs.size() < 2 || !runs.get(0).status().equals("DONE")) return List.of();
        Map<UUID, Map<String, Double>> base = scores(j, runs.get(0).id());
        List<Comparisons.Pair> pairs = new ArrayList<>();
        List<RunView> compared = new ArrayList<>();
        for (RunView run : runs.subList(1, runs.size())) {
            if (!run.status().equals("DONE")) continue;
            Map<UUID, Map<String, Double>> other = scores(j, run.id());
            List<UUID> common = new ArrayList<>(base.keySet());
            common.sort(null);
            common.retainAll(other.keySet());
            Aligned al = align(base, other, common);
            if (al.a().isEmpty()) continue;
            pairs.add(new Comparisons.Pair(run.id().toString(), al.a(), al.b()));
            compared.add(run);
        }
        Map<String, List<Comparisons.Row>> family = Comparisons.compareFamily(pairs);
        List<RunComparison> out = new ArrayList<>();
        for (RunView run : compared) out.add(new RunComparison(run.id(), run.label(), family.get(run.id().toString())));
        return out;
    }

    private static Double finite(Object o) {
        if (o == null) return null;
        double d = ((Number) o).doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored value is unreadable", e);
        }
    }

    private Object readTree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored value is unreadable", e);
        }
    }
}
