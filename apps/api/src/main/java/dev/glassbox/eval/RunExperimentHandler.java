package dev.glassbox.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.eval.ExperimentModel.RunComparison;
import dev.glassbox.eval.ExperimentModel.RunView;
import dev.glassbox.cost.BudgetService;
import dev.glassbox.cost.CostEstimator;
import dev.glassbox.index.IndexService;
import dev.glassbox.jobs.Job;
import dev.glassbox.jobs.JobHandler;
import dev.glassbox.jobs.JobQueue;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
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

    private final Tx tx;
    private final ObjectMapper json;
    private final QuestionEvaluator evaluator;
    private final JobQueue queue;
    private final ExperimentService experiments;
    private final IndexService indexes;
    private final BudgetService budgets;

    public RunExperimentHandler(Tx tx, ObjectMapper json, QuestionEvaluator evaluator, JobQueue queue, ExperimentService experiments,
                                IndexService indexes, BudgetService budgets) {
        this.indexes = indexes;
        this.budgets = budgets;
        this.tx = tx;
        this.json = json;
        this.evaluator = evaluator;
        this.queue = queue;
        this.experiments = experiments;
    }

    @Override
    public String type() {
        return TYPE;
    }

    private record Target(UUID id, UUID testsetId, String status, String mode, Integer limit, Double capUsd, Integer capCalls) {}

    /** Model calls per question in a full run (answer, claim checks, grades): what a call cap is counted in. */
    private static final int LLM_CALLS_PER_QUESTION = 5;

    private record RunRow(UUID id, String label, String status, String config) {}

    @Override
    public void handle(Job job) throws Exception {
        UUID ws = job.workspaceId();
        UUID experimentId = UUID.fromString(job.payload().get("experimentId").asText());
        Target target = tx.workspace(ws, null, j -> j.query("SELECT id, testset_id, status, mode, question_limit, cost_cap_usd, cost_cap_calls FROM experiments WHERE id = ?",
                (rs, n) -> new Target(rs.getObject("id", UUID.class), rs.getObject("testset_id", UUID.class), rs.getString("status"), rs.getString("mode"),
                        (Integer) rs.getObject("question_limit"), rs.getObject("cost_cap_usd") == null ? null : rs.getDouble("cost_cap_usd"),
                        (Integer) rs.getObject("cost_cap_calls")), experimentId)
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
                if (full && abortedByCap(ws, experimentId, target)) return;
                queue.heartbeat(job);
                evaluator.evaluate(ws, run.id(), q, config, full, () -> queue.heartbeat(job));
            }
            evaluator.finishRun(ws, run.id(), loaded);
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

    /** Stores the comparison of every run against the baseline and closes the experiment. */
    /**
     * The abort rule of SPEC 11: a run stops when its actual spend passes 120% of its cap (dollars or model calls). What was measured is kept. The check is made
     * before each question, so a run overshoots its cap by at most one question, which is why the rule is 120% and not 100%.
     */
    private boolean abortedByCap(UUID ws, UUID experimentId, Target target) {
        if (target.capUsd() == null && target.capCalls() == null) return false;
        var totals = tx.workspace(ws, null, j -> j.queryForMap("SELECT coalesce(sum(r.cost_usd), 0) AS usd, count(*) AS n FROM run_results r JOIN runs u ON u.id = r.run_id WHERE u.experiment_id = ?", experimentId));
        double usd = ((Number) totals.get("usd")).doubleValue();
        long calls = actualCalls(ws, experimentId);
        boolean overUsd = CostEstimator.overCap(usd, target.capUsd()), overCalls = CostEstimator.overCap(calls, target.capCalls() == null ? null : target.capCalls());
        if (!overUsd && !overCalls) return false;
        String why = overUsd ? String.format("spend of $%.2f passed 120%% of the $%.2f cap", usd, target.capUsd()) : calls + " model calls passed 120% of the cap of " + target.capCalls();
        tx.workspace(ws, null, j -> {
            j.update("UPDATE experiments SET status = 'ABORTED', error = ?, finished_at = now() WHERE id = ? AND status = 'RUNNING'", "Stopped by its budget: " + why + ". The questions measured so far are kept.", experimentId);
            j.update("UPDATE runs SET status = 'CANCELLED' WHERE experiment_id = ? AND status IN ('PENDING', 'RUNNING')", experimentId);
            return null;
        });
        recordSpend(ws, experimentId, true);
        log.info("experiment {} aborted: {}", experimentId, why);
        return true;
    }

    /**
     * Model calls made so far: the answer and judge calls of a full run, plus the rewriting calls of a configuration with a query transformer (also in a
     * retrieval run). A transformer answer served from the cache is still counted, so this is an upper bound.
     */
    private long actualCalls(UUID ws, UUID experimentId) {
        var rows = tx.workspace(ws, null, j -> j.queryForList("SELECT c.config::text AS config, e.mode AS mode, "
                + "(SELECT count(*) FROM run_results x WHERE x.run_id = r.id) AS results "
                + "FROM runs r JOIN pipeline_configs c ON c.id = r.config_id JOIN experiments e ON e.id = r.experiment_id WHERE r.experiment_id = ?", experimentId));
        long calls = 0;
        for (var row : rows) {
            long results = ((Number) row.get("results")).longValue();
            int perQuestion = "FULL".equals(row.get("mode")) ? LLM_CALLS_PER_QUESTION : 0;
            try {
                var t = json.readValue((String) row.get("config"), PipelineConfig.class).transformer();
                if (t != null) perQuestion += t.llmCalls();
            } catch (Exception e) {
                log.warn("could not read the configuration of a run of experiment {}: {}", experimentId, e.toString());
            }
            calls += results * perQuestion;
        }
        return calls;
    }

    /** Estimate against actual, for the budget page and the estimator's own error. */
    private void recordSpend(UUID ws, UUID experimentId, boolean aborted) {
        var row = tx.workspace(ws, null, j -> j.queryForMap("SELECT e.estimated_cost::text AS estimated, "
                + "(SELECT coalesce(sum(r.cost_usd), 0) FROM run_results r JOIN runs u ON u.id = r.run_id WHERE u.experiment_id = e.id) AS usd, "
                + "(SELECT coalesce(sum(r.tokens_in + r.tokens_out), 0) FROM run_results r JOIN runs u ON u.id = r.run_id WHERE u.experiment_id = e.id) AS tokens, "
                + "(SELECT count(*) FROM run_results r JOIN runs u ON u.id = r.run_id WHERE u.experiment_id = e.id) AS results, e.mode FROM experiments e WHERE e.id = ?", experimentId));
        try {
            var est = json.readTree((String) row.get("estimated"));
            long calls = actualCalls(ws, experimentId);
            budgets.record(ws, "EXPERIMENT", experimentId, est.path("usd").asDouble(0), ((Number) row.get("usd")).doubleValue(), est.path("tokens").asLong(0),
                    ((Number) row.get("tokens")).longValue(), est.path("llmCalls").asInt(0), (int) calls, aborted);
        } catch (Exception e) {
            log.warn("could not record the spend of experiment {}: {}", experimentId, e.toString());
        }
    }

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
        recordSpend(ws, experimentId, false);
    }
}
