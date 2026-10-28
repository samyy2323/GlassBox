package dev.glassbox.ci;

import static dev.glassbox.workspace.WorkspaceService.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Principal;
import dev.glassbox.ci.GateEvaluator.Check;
import dev.glassbox.ci.GateEvaluator.Spec;
import dev.glassbox.ci.GateEvaluator.Stat;
import dev.glassbox.common.ApiException;
import dev.glassbox.eval.Comparisons;
import dev.glassbox.eval.ExperimentModel.ConfigInput;
import dev.glassbox.eval.ExperimentModel.ExperimentView;
import dev.glassbox.eval.ExperimentModel.MetricView;
import dev.glassbox.eval.ExperimentModel.RunView;
import dev.glassbox.eval.ExperimentService;
import dev.glassbox.eval.Metrics;
import dev.glassbox.eval.RetrievalMetrics;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The CI gate (SPEC 14): measures a candidate configuration on a frozen test set and judges it by rules the caller supplies. It is an ordinary experiment (kind
 * REGRESSION, so it stays out of the way of manual ones) plus a stored rule set, which means the budget, the cap on concurrent runs, the queue, the audit log and the
 * isolation tests all apply to it without any gate-specific code.
 */
@Service
public class CiGateService {
    public record Request(UUID testsetId, String name, String mode, JsonNode config, UUID baselineRunId, Map<String, Double> failUnder,
                          Map<String, Double> failOnRegression, Boolean strict, Double alpha, Double capUsd, Integer capCalls) {}

    public record Started(UUID id, String status) {}

    /** {@code state}: RUNNING, PASSED, FAILED (a rule was broken) or ERROR (the run could not be judged: it failed, was stopped, or has nothing to compare with). */
    public record Result(UUID id, String name, String state, String status, Boolean passed, String note, String testset, int questions, UUID runId, UUID baselineRunId,
                         List<Check> checks) {}

    private final Tx tx;
    private final ObjectMapper json;
    private final ExperimentService experiments;

    public CiGateService(Tx tx, ObjectMapper json, ExperimentService experiments) {
        this.tx = tx;
        this.json = json;
        this.experiments = experiments;
    }

    public Started start(Principal p, UUID ws, Request r) {
        if (r.testsetId() == null) throw ApiException.badRequest("testsetId is required");
        String mode = r.mode() == null ? "RETRIEVAL" : r.mode().toUpperCase(java.util.Locale.ROOT);
        if (!mode.equals("RETRIEVAL") && !mode.equals("FULL")) throw ApiException.badRequest("mode must be RETRIEVAL or FULL");
        Map<String, Double> under = r.failUnder() == null ? Map.of() : r.failUnder();
        Map<String, Double> regress = r.failOnRegression() == null ? Map.of() : r.failOnRegression();
        if (under.isEmpty() && regress.isEmpty()) throw ApiException.badRequest("Give at least one rule: failUnder or failOnRegression");
        if (!regress.isEmpty() && r.baselineRunId() == null) throw ApiException.badRequest("failOnRegression needs a baselineRunId to compare with");
        for (var rules : List.of(under, regress)) {
            for (var e : rules.entrySet()) {
                List<String> known = mode.equals("FULL") ? Metrics.ALL : RetrievalMetrics.ALL;
                if (!known.contains(e.getKey())) throw ApiException.badRequest("Unknown metric \"" + e.getKey() + "\" for a " + mode.toLowerCase() + " run. Available: " + known);
                if (e.getValue() == null || e.getValue() < 0 || e.getValue() > 1) throw ApiException.badRequest("Thresholds are fractions between 0 and 1 (0.85 means 85%)");
            }
        }
        double alpha = r.alpha() == null ? 0.05 : r.alpha();
        if (alpha <= 0 || alpha >= 1) throw ApiException.badRequest("alpha must be between 0 and 1");
        String name = r.name() == null || r.name().isBlank() ? "CI gate" : r.name().strip();

        if (r.baselineRunId() != null) checkBaseline(ws, p, r.baselineRunId(), r.testsetId());
        ConfigInput config;
        try {
            config = json.treeToValue(r.config() == null || r.config().isNull() ? json.createObjectNode().put("name", "candidate") : withName(r.config()), ConfigInput.class);
        } catch (Exception e) {
            throw ApiException.badRequest("config is not a valid pipeline configuration: " + e.getMessage());
        }
        Spec spec = new Spec(mode, r.baselineRunId(), under, regress, Boolean.TRUE.equals(r.strict()), alpha);
        ExperimentView started = experiments.create(p, ws, r.testsetId(), name, mode, List.of(config), null,
                new ExperimentService.Limits(r.capUsd(), r.capCalls(), true), "REGRESSION");
        tx.workspace(ws, p.userId(), j -> {
            j.update("INSERT INTO ci_gates (experiment_id, workspace_id, spec, created_by) VALUES (?, ?, ?::jsonb, ?)", started.id(), ws, write(spec), p.userId());
            audit(j, ws, p.actor(), "ci.run", "experiment:" + started.id());
            return null;
        });
        return new Started(started.id(), started.status());
    }

    public Result result(Principal p, UUID ws, UUID id) {
        Spec spec = tx.workspace(ws, p.userId(), j -> {
            var rows = j.queryForList("SELECT spec::text FROM ci_gates WHERE experiment_id = ?", String.class, id);
            return rows.isEmpty() ? null : read(rows.get(0));
        });
        if (spec == null) throw ApiException.notFound("No such gate run");
        ExperimentView v = experiments.get(p, ws, id);
        RunView run = v.runs().isEmpty() ? null : v.runs().get(0);
        UUID runId = run == null ? null : run.id();
        String testset = v.testsetName() + " v" + v.testsetVersion();
        if (v.status().equals("QUEUED") || v.status().equals("RUNNING")) {
            return new Result(id, v.name(), "RUNNING", v.status(), null, "Measuring " + v.questionCount() + " questions.", testset, v.questionCount(), runId, spec.baselineRunId(), List.of());
        }
        if (!v.status().equals("DONE") || run == null) {
            String why = v.error() != null ? v.error() : "The run ended as " + v.status() + " before it was finished.";
            return new Result(id, v.name(), "ERROR", v.status(), null, why, testset, v.questionCount(), runId, spec.baselineRunId(), List.of());
        }

        Map<String, Stat> candidate = new LinkedHashMap<>();
        for (MetricView m : run.metrics()) {
            if ("all".equals(m.slice()) && "question".equals(m.method()) && m.value() != null) candidate.put(m.metric(), new Stat(m.value(), m.ciLow(), m.ciHigh(), m.n()));
        }
        Map<String, Comparisons.Row> rows = null;
        if (spec.baselineRunId() != null && !spec.failOnRegression().isEmpty()) {
            try {
                rows = new LinkedHashMap<>();
                for (Comparisons.Row row : experiments.compare(p, ws, spec.baselineRunId(), run.id()).rows()) rows.put(row.metric(), row);
            } catch (ApiException e) {
                return new Result(id, v.name(), "ERROR", v.status(), null, e.getMessage(), testset, v.questionCount(), runId, spec.baselineRunId(), List.of());
            }
        }
        List<Check> checks = new ArrayList<>(GateEvaluator.evaluate(spec, candidate, rows));
        boolean passed = GateEvaluator.passed(checks);
        return new Result(id, v.name(), passed ? "PASSED" : "FAILED", v.status(), passed, null, testset, v.questionCount(), runId, spec.baselineRunId(), checks);
    }

    private void checkBaseline(UUID ws, Principal p, UUID baselineRunId, UUID testsetId) {
        var rows = tx.workspace(ws, p.userId(), j -> j.queryForList("SELECT r.status, e.testset_id FROM runs r JOIN experiments e ON e.id = r.experiment_id WHERE r.id = ?", baselineRunId));
        if (rows.isEmpty()) throw ApiException.notFound("No such baseline run");
        if (!"DONE".equals(rows.get(0).get("status"))) throw ApiException.conflict("The baseline run has not finished");
        if (!testsetId.equals(rows.get(0).get("testset_id"))) {
            throw ApiException.conflict("The baseline run measured a different test set version, so its questions cannot be compared with this run's. Run the baseline again on this test set.");
        }
    }

    /** A configuration needs a name; the caller may leave it out. */
    private JsonNode withName(JsonNode config) {
        if (config.hasNonNull("name")) return config;
        var copy = config.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).put("name", "candidate");
        return copy;
    }

    private String write(Spec spec) {
        try {
            return json.writeValueAsString(spec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Spec read(String text) {
        try {
            return json.readValue(text, Spec.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
