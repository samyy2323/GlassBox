package dev.glassbox.diagnose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.diagnose.FailureClassifier.Stage;
import dev.glassbox.diagnose.FailureClassifier.Sub;
import dev.glassbox.diagnose.Recommendations.Recommendation;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

import static dev.glassbox.workspace.WorkspaceService.require;

/** The diagnosis report of one run: failures by stage, stage by question type, and what to try about them (SPEC 9). */
@Service
public class DiagnosisService {
    public record Failure(UUID questionId, String question, String qtype, Stage stage, Sub sub, Stage lostAt, UUID traceId) {}

    public record Diagnosis(UUID experimentId, UUID runId, String runLabel, String mode, PipelineConfig config, int questions, int failed,
                            Map<Stage, Integer> byStage, Map<Stage, Map<String, Integer>> byStageAndType, Map<Sub, Integer> bySub,
                            List<Recommendation> recommendations, List<Failure> failures) {}

    private final Tx tx;
    private final ObjectMapper json;

    public DiagnosisService(Tx tx, ObjectMapper json) {
        this.tx = tx;
        this.json = json;
    }

    public Diagnosis diagnose(Principal p, UUID ws, UUID experimentId, UUID runId) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            var run = j.query("SELECT r.label, e.mode, c.config::text AS config FROM runs r JOIN experiments e ON e.id = r.experiment_id "
                            + "JOIN pipeline_configs c ON c.id = r.config_id WHERE r.id = ? AND r.experiment_id = ?",
                    (rs, n) -> new String[] {rs.getString("label"), rs.getString("mode"), rs.getString("config")}, runId, experimentId);
            if (run.isEmpty()) throw ApiException.notFound("No such run");
            PipelineConfig config;
            try {
                config = json.readValue(run.get(0)[2], PipelineConfig.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            Map<Stage, Integer> byStage = Recommendations.zeroed();
            Map<Stage, Map<String, Integer>> matrix = new LinkedHashMap<>();
            for (Stage s : Stage.values()) matrix.put(s, new LinkedHashMap<>());
            Map<Sub, Integer> bySub = new LinkedHashMap<>();
            List<Failure> failures = new ArrayList<>();
            int[] total = {0};
            j.query("SELECT r.question_id, q.question, q.qtype, r.failure_stage, r.failure_detail::text AS detail, r.trace_id "
                    + "FROM run_results r JOIN test_questions q ON q.id = r.question_id WHERE r.run_id = ? ORDER BY q.seq", rs -> {
                total[0]++;
                String stageName = rs.getString("failure_stage");
                if (stageName == null) return;
                Stage stage;
                try {
                    stage = Stage.valueOf(stageName);
                } catch (IllegalArgumentException old) {
                    return; // a label from before the taxonomy was complete
                }
                Sub sub = null;
                Stage lostAt = null;
                String detail = rs.getString("detail");
                if (detail != null) {
                    try {
                        JsonNode d = json.readTree(detail);
                        if (d.hasNonNull("sub")) sub = Sub.valueOf(d.get("sub").asText());
                        if (d.hasNonNull("lostAt")) lostAt = Stage.valueOf(d.get("lostAt").asText());
                    } catch (Exception ignored) {
                        // a detail that cannot be read still counts as a failure at its stage
                    }
                }
                byStage.merge(stage, 1, Integer::sum);
                matrix.get(stage).merge(rs.getString("qtype"), 1, Integer::sum);
                if (sub != null) bySub.merge(sub, 1, Integer::sum);
                failures.add(new Failure(rs.getObject("question_id", UUID.class), rs.getString("question"), rs.getString("qtype"), stage, sub, lostAt,
                        rs.getObject("trace_id", UUID.class)));
            }, runId);

            Map<Stage, Integer> present = new LinkedHashMap<>();
            byStage.forEach((s, n) -> { if (n > 0) present.put(s, n); });
            return new Diagnosis(experimentId, runId, run.get(0)[0], run.get(0)[1], config, total[0], failures.size(), byStage, matrix, bySub,
                    Recommendations.build(present, bySub, config), failures);
        });
    }
}
