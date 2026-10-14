package dev.glassbox.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.eval.ExperimentModel.RunComparison;
import dev.glassbox.eval.ExperimentModel.RunView;
import dev.glassbox.eval.RetrievalMetrics.Gold;
import dev.glassbox.eval.RetrievalMetrics.Ref;
import dev.glassbox.jobs.Job;
import dev.glassbox.jobs.JobHandler;
import dev.glassbox.jobs.JobQueue;
import dev.glassbox.retrieval.Candidate;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalPipeline;
import dev.glassbox.retrieval.Span;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs an experiment: for every run and every measurable question it retrieves with the run's configuration, scores the result
 * against the gold evidence and stores it. Results are unique per (run, question), so a crashed or repeated job resumes where it
 * stopped and can never count a question twice. The job reports in to the queue after every question, because a long experiment
 * would otherwise be handed to a second worker when its lock expires (the same lesson as test set generation).
 */
@Component
public class RunExperimentHandler implements JobHandler {
    public static final String TYPE = "RUN_EXPERIMENT";
    private static final Logger log = LoggerFactory.getLogger(RunExperimentHandler.class);
    private static final int KEPT_CANDIDATES = 20;

    private final Tx tx;
    private final ObjectMapper json;
    private final RetrievalPipeline pipeline;
    private final JobQueue queue;
    private final ExperimentService experiments;

    public RunExperimentHandler(Tx tx, ObjectMapper json, RetrievalPipeline pipeline, JobQueue queue, ExperimentService experiments) {
        this.tx = tx;
        this.json = json;
        this.pipeline = pipeline;
        this.queue = queue;
        this.experiments = experiments;
    }

    @Override
    public String type() {
        return TYPE;
    }

    private record Target(UUID id, UUID testsetId, String status) {}

    private record RunRow(UUID id, String label, String status, String config) {}

    @Override
    public void handle(Job job) throws Exception {
        UUID ws = job.workspaceId();
        UUID experimentId = UUID.fromString(job.payload().get("experimentId").asText());
        Target target = tx.workspace(ws, null, j -> j.query("SELECT id, testset_id, status FROM experiments WHERE id = ?",
                (rs, n) -> new Target(rs.getObject("id", UUID.class), rs.getObject("testset_id", UUID.class), rs.getString("status")), experimentId)
                .stream().findFirst().orElse(null));
        if (target == null || !(target.status().equals("QUEUED") || target.status().equals("RUNNING"))) return;
        tx.workspace(ws, null, j -> j.update("UPDATE experiments SET status = 'RUNNING' WHERE id = ? AND status = 'QUEUED'", experimentId));

        MeasurableQuestions.Loaded loaded = tx.workspace(ws, null, j -> MeasurableQuestions.load(j, json, target.testsetId()));
        List<RunRow> runs = tx.workspace(ws, null, j -> j.query("SELECT r.id, r.label, r.status, c.config::text AS config FROM runs r JOIN pipeline_configs c ON c.id = r.config_id "
                + "WHERE r.experiment_id = ? ORDER BY r.ordinal", (rs, n) -> new RunRow(rs.getObject("id", UUID.class), rs.getString("label"), rs.getString("status"),
                rs.getString("config")), experimentId));

        for (RunRow run : runs) {
            if (run.status().equals("DONE")) continue;
            PipelineConfig config = json.readValue(run.config(), PipelineConfig.class);
            tx.workspace(ws, null, j -> j.update("UPDATE runs SET status = 'RUNNING', started_at = coalesce(started_at, now()) WHERE id = ?", run.id()));
            Set<UUID> done = new HashSet<>(tx.workspace(ws, null, j -> j.queryForList("SELECT question_id FROM run_results WHERE run_id = ?", UUID.class, run.id())));
            for (MeasurableQuestions.Question q : loaded.questions()) {
                if (done.contains(q.id())) continue;
                if (cancelled(ws, experimentId)) return;
                queue.heartbeat(job);
                store(ws, run.id(), q, config);
            }
            writeMetrics(ws, run.id(), loaded);
        }
        finish(ws, experimentId, loaded);
        log.info("experiment {}: {} runs on {} questions", experimentId, runs.size(), loaded.questions().size());
    }

    @Override
    public void onDead(Job job, String error) {
        UUID experimentId = UUID.fromString(job.payload().get("experimentId").asText());
        tx.workspace(job.workspaceId(), null, j -> {
            j.update("UPDATE experiments SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ? AND status IN ('QUEUED', 'RUNNING')",
                    "Stopped: " + (error == null ? "unknown error" : error), experimentId);
            j.update("UPDATE runs SET status = 'FAILED' WHERE experiment_id = ? AND status IN ('PENDING', 'RUNNING')", experimentId);
            return null;
        });
    }

    private boolean cancelled(UUID ws, UUID experimentId) {
        String status = tx.workspace(ws, null, j -> j.queryForObject("SELECT status FROM experiments WHERE id = ?", String.class, experimentId));
        return !status.equals("RUNNING");
    }

    private static Ref ref(Candidate c) {
        return new Ref(c.documentId(), c.charStart(), c.charEnd());
    }

    /** Retrieves for one question, scores it, and writes the result once. */
    private void store(UUID ws, UUID runId, MeasurableQuestions.Question q, PipelineConfig config) throws JsonProcessingException {
        RetrievalPipeline.Result result = pipeline.retrieve(ws, q.text(), config);
        List<Gold> gold = q.spans().stream().map(s -> new Gold(s.documentId(), s.charStart(), s.charEnd())).toList();
        List<Ref> ranked = result.reranked().stream().map(RunExperimentHandler::ref).toList();
        List<Ref> context = result.context().included().stream().map(c -> ref(c.chunk())).toList();
        Map<String, Double> metrics = RetrievalMetrics.compute(ranked, context, gold);
        long latency = result.spans().stream().mapToLong(Span::millis).sum();

        List<Map<String, Object>> retrieved = new ArrayList<>();
        for (int i = 0; i < Math.min(KEPT_CANDIDATES, result.reranked().size()); i++) {
            Candidate c = result.reranked().get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rank", i + 1);
            m.put("chunkId", c.chunkId());
            m.put("documentId", c.documentId());
            m.put("charStart", c.charStart());
            m.put("charEnd", c.charEnd());
            m.put("hit", gold.stream().anyMatch(g -> RetrievalMetrics.hits(ref(c), g)));
            if (c.vecRank() != null) m.put("vecRank", c.vecRank());
            if (c.kwRank() != null) m.put("kwRank", c.kwRank());
            retrieved.add(m);
        }
        List<Map<String, Object>> finalContext = new ArrayList<>();
        result.context().included().forEach(c -> finalContext.add(Map.of("label", c.label(), "chunkId", c.chunk().chunkId(), "documentId", c.chunk().documentId(),
                "charStart", c.chunk().charStart(), "charEnd", c.chunk().charEnd())));

        String failure = null;
        if (metrics.get(RetrievalMetrics.CONTEXT_PASS) < 1.0) {
            if (RetrievalMetrics.recallAtK(result.candidates().stream().map(RunExperimentHandler::ref).toList(), gold, Integer.MAX_VALUE) < 1.0) failure = "RETRIEVAL_MISS";
            else if (RetrievalMetrics.recallAtK(result.ranked().stream().map(RunExperimentHandler::ref).toList(), gold, Integer.MAX_VALUE) < 1.0) failure = "RANKING_MISS";
            else failure = "CONTEXT_MISS";
        }
        String fail = failure;
        String metricsJson = json.writeValueAsString(metrics), retrievedJson = json.writeValueAsString(retrieved), contextJson = json.writeValueAsString(finalContext);
        tx.workspace(ws, null, j -> {
            int inserted = j.update("INSERT INTO run_results (workspace_id, run_id, question_id, retrieved, final_context, metrics, failure_stage, latency_ms) "
                    + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?) ON CONFLICT (run_id, question_id) DO NOTHING",
                    ws, runId, q.id(), retrievedJson, contextJson, metricsJson, fail, (int) latency);
            if (inserted > 0) j.update("UPDATE runs SET samples_done = samples_done + 1 WHERE id = ?", runId);
            return null;
        });
    }

    /** Aggregates a finished run into run_metrics and marks it DONE. Rewritten from scratch each time, so a resumed run never doubles rows. */
    private void writeMetrics(UUID ws, UUID runId, MeasurableQuestions.Loaded loaded) {
        Map<UUID, MeasurableQuestions.Question> byId = new HashMap<>();
        loaded.questions().forEach(q -> byId.put(q.id(), q));
        Map<UUID, Integer> clusterIds = new HashMap<>();
        List<RunAggregates.Score> scores = new ArrayList<>();
        tx.workspace(ws, null, j -> {
            j.query("SELECT question_id, metrics::text AS metrics, latency_ms FROM run_results WHERE run_id = ? ORDER BY question_id", rs -> {
                MeasurableQuestions.Question q = byId.get(rs.getObject("question_id", UUID.class));
                if (q == null) return;
                try {
                    Map<String, Double> m = json.readValue(rs.getString("metrics"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Double>>() {});
                    int cluster = clusterIds.computeIfAbsent(q.clusterDocument(), d -> clusterIds.size());
                    scores.add(new RunAggregates.Score(q.qtype(), cluster, m, rs.getInt("latency_ms")));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }, runId);
            return null;
        });
        List<RunAggregates.Row> rows = RunAggregates.compute(scores, runId.hashCode());
        tx.workspace(ws, null, j -> {
            j.update("DELETE FROM run_metrics WHERE run_id = ?", runId);
            for (RunAggregates.Row r : rows) {
                j.update("INSERT INTO run_metrics (run_id, workspace_id, metric, slice, method, value, ci_low, ci_high, n) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        runId, ws, r.metric(), r.slice(), r.method(), nan(r.value()), nan(r.ciLow()), nan(r.ciHigh()), r.n());
            }
            j.update("UPDATE runs SET status = 'DONE', finished_at = now() WHERE id = ?", runId);
            return null;
        });
    }

    private static Double nan(double d) {
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    /** Stores the comparison of every run against the baseline and closes the experiment. */
    private void finish(UUID ws, UUID experimentId, MeasurableQuestions.Loaded loaded) {
        tx.workspace(ws, null, j -> {
            var runRows = j.queryForList("SELECT id, label, ordinal, status, samples_done FROM runs WHERE experiment_id = ? ORDER BY ordinal", experimentId);
            List<RunView> views = new ArrayList<>();
            for (var r : runRows) views.add(new RunView((UUID) r.get("id"), (String) r.get("label"), (Integer) r.get("ordinal"), (String) r.get("status"),
                    (Integer) r.get("samples_done"), null, List.of()));
            for (RunComparison rc : experiments.baselineComparisons(j, views)) {
                for (var row : rc.rows()) {
                    j.update("INSERT INTO comparisons (workspace_id, run_a, run_b, metric, delta, ci_low, ci_high, p_value, p_adjusted, verdict) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                    + "ON CONFLICT (run_a, run_b, metric) DO UPDATE SET delta = EXCLUDED.delta, ci_low = EXCLUDED.ci_low, ci_high = EXCLUDED.ci_high, "
                                    + "p_value = EXCLUDED.p_value, p_adjusted = EXCLUDED.p_adjusted, verdict = EXCLUDED.verdict",
                            ws, views.get(0).id(), rc.runId(), row.metric(), row.delta(), row.ciLow(), row.ciHigh(), row.p(), row.pAdjusted(), row.verdict());
                }
            }
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("questions", loaded.questions().size());
            actual.put("retrievalCalls", loaded.questions().size() * runRows.size());
            actual.put("llmCalls", 0);
            actual.put("usd", 0.0);
            try {
                j.update("UPDATE experiments SET status = 'DONE', actual_cost = ?::jsonb, finished_at = now() WHERE id = ? AND status = 'RUNNING'", json.writeValueAsString(actual), experimentId);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }
}
