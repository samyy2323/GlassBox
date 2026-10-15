package dev.glassbox.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.diagnose.FailureClassifier;
import dev.glassbox.eval.ExperimentModel.RunComparison;
import dev.glassbox.eval.ExperimentModel.RunView;
import dev.glassbox.eval.RetrievalMetrics.Gold;
import dev.glassbox.eval.RetrievalMetrics.Ref;
import dev.glassbox.index.IndexService;
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
 * against the gold evidence and stores it; a full experiment also writes the answer, verifies its claims and has it judged
 * ({@link FullScorer}). Results are unique per (run, question), so a crashed or repeated job resumes where it stopped and can never
 * count a question twice. The job reports in to the queue after every model call, because a long experiment would otherwise be handed
 * to a second worker when its lock expires (the same lesson as test set generation).
 */
@Component
public class RunExperimentHandler implements JobHandler {
    public static final String TYPE = "RUN_EXPERIMENT";
    private static final Logger log = LoggerFactory.getLogger(RunExperimentHandler.class);
    private static final int KEPT_CANDIDATES = 20;
    private static final UUID NO_DOCUMENT = new UUID(0, 0);

    private final Tx tx;
    private final ObjectMapper json;
    private final RetrievalPipeline pipeline;
    private final JobQueue queue;
    private final ExperimentService experiments;
    private final FullScorer scorer;
    private final IndexService indexes;

    public RunExperimentHandler(Tx tx, ObjectMapper json, RetrievalPipeline pipeline, JobQueue queue, ExperimentService experiments, FullScorer scorer,
                                IndexService indexes) {
        this.indexes = indexes;
        this.tx = tx;
        this.json = json;
        this.pipeline = pipeline;
        this.queue = queue;
        this.experiments = experiments;
        this.scorer = scorer;
    }

    @Override
    public String type() {
        return TYPE;
    }

    private record Target(UUID id, UUID testsetId, String status, String mode, Integer limit) {}

    private record RunRow(UUID id, String label, String status, String config) {}

    @Override
    public void handle(Job job) throws Exception {
        UUID ws = job.workspaceId();
        UUID experimentId = UUID.fromString(job.payload().get("experimentId").asText());
        Target target = tx.workspace(ws, null, j -> j.query("SELECT id, testset_id, status, mode, question_limit FROM experiments WHERE id = ?",
                (rs, n) -> new Target(rs.getObject("id", UUID.class), rs.getObject("testset_id", UUID.class), rs.getString("status"), rs.getString("mode"),
                        (Integer) rs.getObject("question_limit")), experimentId)
                .stream().findFirst().orElse(null));
        if (target == null || !(target.status().equals("QUEUED") || target.status().equals("RUNNING"))) return;
        tx.workspace(ws, null, j -> j.update("UPDATE experiments SET status = 'RUNNING' WHERE id = ? AND status = 'QUEUED'", experimentId));
        boolean full = target.mode().equals("FULL");

        MeasurableQuestions.Loaded loaded = tx.workspace(ws, null, j -> MeasurableQuestions.load(j, json, target.testsetId(), full, target.limit()));
        List<RunRow> runs = tx.workspace(ws, null, j -> j.query("SELECT r.id, r.label, r.status, c.config::text AS config FROM runs r JOIN pipeline_configs c ON c.id = r.config_id "
                + "WHERE r.experiment_id = ? ORDER BY r.ordinal", (rs, n) -> new RunRow(rs.getObject("id", UUID.class), rs.getString("label"), rs.getString("status"),
                rs.getString("config")), experimentId));

        // Chunkings and embedding models the uploads did not create are built first, for every document, so runs compare pipelines and not corpora.
        List<PipelineConfig> configs = new ArrayList<>();
        for (RunRow run : runs) if (!run.status().equals("DONE")) configs.add(json.readValue(run.config(), PipelineConfig.class));
        for (IndexService.Need need : indexes.needs(configs)) {
            if (cancelled(ws, experimentId)) return;
            indexes.ensure(ws, need, () -> queue.heartbeat(job));
        }

        for (RunRow run : runs) {
            if (run.status().equals("DONE")) continue;
            PipelineConfig config = json.readValue(run.config(), PipelineConfig.class);
            tx.workspace(ws, null, j -> j.update("UPDATE runs SET status = 'RUNNING', started_at = coalesce(started_at, now()) WHERE id = ?", run.id()));
            Set<UUID> done = new HashSet<>(tx.workspace(ws, null, j -> j.queryForList("SELECT question_id FROM run_results WHERE run_id = ?", UUID.class, run.id())));
            for (MeasurableQuestions.Question q : loaded.questions()) {
                if (done.contains(q.id())) continue;
                if (cancelled(ws, experimentId)) return;
                queue.heartbeat(job);
                store(ws, run.id(), q, config, full, () -> queue.heartbeat(job));
            }
            writeMetrics(ws, run.id(), loaded);
        }
        finish(ws, experimentId, loaded, runs.size());
        log.info("experiment {}: {} runs on {} questions ({})", experimentId, runs.size(), loaded.questions().size(), target.mode());
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

    /** Retrieves for one question, scores it (and, in a full run, answers and judges it), and writes the result once. */
    private void store(UUID ws, UUID runId, MeasurableQuestions.Question q, PipelineConfig config, boolean full, Runnable alive) throws JsonProcessingException {
        long started = System.nanoTime();
        RetrievalPipeline.Result result = pipeline.retrieve(ws, q.text(), config);
        List<Gold> gold = q.spans().stream().map(s -> new Gold(s.documentId(), s.charStart(), s.charEnd())).toList();
        Map<String, Double> metrics = new LinkedHashMap<>();
        String failure = null;
        List<Map<String, Object>> retrieved = new ArrayList<>(), finalContext = new ArrayList<>();

        if (!gold.isEmpty()) {
            List<Ref> ranked = result.reranked().stream().map(RunExperimentHandler::ref).toList();
            List<Ref> context = result.context().included().stream().map(c -> ref(c.chunk())).toList();
            metrics.putAll(RetrievalMetrics.compute(ranked, context, gold));
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
        }
        result.context().included().forEach(c -> finalContext.add(Map.of("label", c.label(), "chunkId", c.chunk().chunkId(), "documentId", c.chunk().documentId(),
                "charStart", c.chunk().charStart(), "charEnd", c.chunk().charEnd())));

        String answer = null;
        Object claims = null;
        int tokensIn = 0, tokensOut = 0;
        double cost = 0;
        UUID traceId = null;
        if (full) {
            FullScorer.Scored scored = scorer.score(ws, q, result, alive);
            metrics.putAll(scored.metrics());
            answer = scored.answer();
            claims = scored.claimsJson();
            tokensIn = scored.tokensIn();
            tokensOut = scored.tokensOut();
            cost = scored.costUsd();
            traceId = scored.traceId();
        }
        long latency = full ? (System.nanoTime() - started) / 1_000_000 : result.spans().stream().mapToLong(Span::millis).sum();

        List<FailureClassifier.SpanTrace> traces = new ArrayList<>();
        List<Map<String, Object>> spanDetail = new ArrayList<>();
        for (Gold g : gold) traceSpan(ws, result, g, traces, spanDetail);
        boolean abstained = full && (q.spans().isEmpty() ? metrics.getOrDefault(Metrics.ABSTENTION_ACCURACY, 0.0) >= 1.0
                : metrics.getOrDefault(Metrics.FALSE_ABSTENTION, 0.0) >= 1.0);
        FailureClassifier.Diagnosis diagnosis = FailureClassifier.classify(new FailureClassifier.Input(q.qtype(), traces, full, abstained,
                metrics.get(Metrics.CORRECTNESS), metrics.get(Metrics.FAITHFULNESS), q.flags().contains("AMBIGUOUS") || q.flags().contains("LOW_ANSWERABILITY")));
        failure = diagnosis == null ? null : diagnosis.stage().name();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("spans", spanDetail);
        if (diagnosis != null) {
            detail.put("stage", diagnosis.stage());
            if (diagnosis.sub() != null) detail.put("sub", diagnosis.sub());
            if (diagnosis.lostAt() != null) detail.put("lostAt", diagnosis.lostAt());
        }
        String detailJson = json.writeValueAsString(detail);

        String metricsJson = json.writeValueAsString(metrics), retrievedJson = json.writeValueAsString(retrieved), contextJson = json.writeValueAsString(finalContext);
        String claimsJson = claims == null ? null : json.writeValueAsString(claims);
        String fail = failure, ans = answer;
        int tin = tokensIn, tout = tokensOut;
        double usd = cost;
        UUID trace = traceId;
        tx.workspace(ws, null, j -> {
            int inserted = j.update("INSERT INTO run_results (workspace_id, run_id, question_id, retrieved, final_context, answer, claims, metrics, failure_stage, latency_ms, "
                            + "tokens_in, tokens_out, cost_usd, trace_id, failure_detail) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb) "
                            + "ON CONFLICT (run_id, question_id) DO NOTHING",
                    ws, runId, q.id(), retrievedJson, contextJson, ans, claimsJson, metricsJson, fail, (int) latency, tin, tout, usd, trace, detailJson);
            if (inserted > 0) j.update("UPDATE runs SET samples_done = samples_done + 1 WHERE id = ?", runId);
            return null;
        });
    }

    /**
     * Follows one gold span through the pipeline. The chunk-size lookup (does any single chunk of this chunking cover the span?) is only made
     * for a span that did not reach the prompt, since only then does it decide the label.
     */
    private void traceSpan(UUID ws, RetrievalPipeline.Result result, Gold g, List<FailureClassifier.SpanTrace> traces, List<Map<String, Object>> detail) {
        Integer fusedRank = firstHit(result.candidates(), g), finalRank = firstHit(result.reranked(), g);
        boolean inRanked = firstHit(result.ranked(), g) != null;
        boolean inContext = result.context().included().stream().anyMatch(c -> RetrievalMetrics.hits(ref(c.chunk()), g));
        Double cover = null;
        String dropped = null;
        if (!inContext) {
            cover = result.chunkingId() == null ? 0.0 : bestCover(ws, result.chunkingId(), g);
            dropped = result.context().dropped().stream().filter(d -> RetrievalMetrics.hits(ref(d.chunk()), g)).map(d -> d.reason().name()).findFirst().orElse(null);
        }
        traces.add(new FailureClassifier.SpanTrace(cover, finalRank != null, inRanked, inContext));
        Map<String, Object> m = new LinkedHashMap<>();
        if (cover != null) m.put("bestCover", Math.round(cover * 1000) / 1000.0);
        if (fusedRank != null) m.put("fusedRank", fusedRank);
        if (finalRank != null) m.put("finalRank", finalRank);
        m.put("inRanked", inRanked);
        m.put("inContext", inContext);
        if (dropped != null) m.put("dropped", dropped);
        detail.add(m);
    }

    /** 1-based position of the first chunk that hits the span, or null. */
    private static Integer firstHit(List<Candidate> list, Gold g) {
        for (int i = 0; i < list.size(); i++) if (RetrievalMetrics.hits(ref(list.get(i)), g)) return i + 1;
        return null;
    }

    /** The largest share of the span that one chunk of the chunking covers. */
    private double bestCover(UUID ws, UUID chunkingId, Gold g) {
        int length = g.end() - g.start();
        if (length <= 0) return 0;
        Integer overlap = tx.workspace(ws, null, j -> j.queryForObject(
                "SELECT coalesce(max(least(char_end, ?) - greatest(char_start, ?)), 0) FROM chunks "
                        + "WHERE chunking_id = ? AND document_id = ? AND NOT superseded AND char_end > ? AND char_start < ?",
                Integer.class, g.end(), g.start(), chunkingId, g.documentId(), g.start(), g.end()));
        return Math.min(1.0, Math.max(0, overlap == null ? 0 : overlap) / (double) length);
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
                    Map<String, Double> m = json.readValue(rs.getString("metrics"), new TypeReference<Map<String, Double>>() {});
                    UUID doc = q.clusterDocument() == null ? NO_DOCUMENT : q.clusterDocument();
                    int cluster = clusterIds.computeIfAbsent(doc, d -> clusterIds.size());
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
    private void finish(UUID ws, UUID experimentId, MeasurableQuestions.Loaded loaded, int runCount) {
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
            var totals = j.queryForMap("SELECT coalesce(sum(r.tokens_in), 0) AS tin, coalesce(sum(r.tokens_out), 0) AS tout, coalesce(sum(r.cost_usd), 0) AS usd "
                    + "FROM run_results r JOIN runs u ON u.id = r.run_id WHERE u.experiment_id = ?", experimentId);
            Map<String, Object> actual = new LinkedHashMap<>();
            actual.put("questions", loaded.questions().size());
            actual.put("retrievalCalls", loaded.questions().size() * runCount);
            actual.put("tokensIn", ((Number) totals.get("tin")).longValue());
            actual.put("tokensOut", ((Number) totals.get("tout")).longValue());
            actual.put("usd", ((Number) totals.get("usd")).doubleValue());
            try {
                j.update("UPDATE experiments SET status = 'DONE', actual_cost = ?::jsonb, finished_at = now() WHERE id = ? AND status = 'RUNNING'", json.writeValueAsString(actual), experimentId);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }
}
