package dev.glassbox.eval;

import static dev.glassbox.workspace.WorkspaceService.audit;
import static dev.glassbox.workspace.WorkspaceService.require;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.generate.LlmProvider;
import dev.glassbox.stats.Cohen;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Judge calibration (SPEC 8): a person labels a sample of what the judge graded, without seeing the judge's verdict, and the two are
 * compared with percent agreement, Cohen's kappa, a confusion matrix and precision and recall per verdict. The result is saved per judge
 * model and rubric version; judged metrics from a judge below the trusted kappa carry an "uncalibrated" badge everywhere.
 */
@Service
public class CalibrationService {
    /** At least this many labels before kappa means anything (SPEC 8: a sample of 30 to 50). */
    public static final int MIN_LABELS = 30;
    public static final int DEFAULT_SAMPLE = 40;

    /** The verdicts a human may give for each judged metric: the same words the judge uses. */
    public static final Map<String, List<String>> LABELS = Map.of(
            Metrics.FAITHFULNESS, List.of("supported", "partial", "unsupported"),
            Metrics.CORRECTNESS, List.of("correct", "partial", "wrong"),
            Metrics.RELEVANCE, List.of("yes", "partly", "no"));

    /** An item to label. There is deliberately no field for the judge's verdict. */
    public record Item(UUID labelId, String metric, String itemKey, String question, String expectedAnswer, String answer, String claim, String evidence,
                       List<String> options) {}

    public record Sample(String metric, int size, int stillUnlabeled, int target, List<Item> items) {}

    public record HumanLabel(UUID labelId, String humanLabel) {}

    public record MetricSummary(String metric, String judgeModel, String rubricVersion, int n, Double agreement, Double kappa, String status, boolean needsMoreLabels,
                                List<String> labels, int[][] confusion, Map<String, Double> precision, Map<String, Double> recall, int unlabeled,
                                OffsetDateTime updatedAt) {}

    public record Summary(String judgeModel, String rubricVersion, int minimumLabels, double trustedKappa, List<MetricSummary> metrics, List<String> warnings) {}

    private final Tx tx;
    private final ObjectMapper json;
    private final LlmProvider llm;
    private final NliProvider nli;

    public CalibrationService(Tx tx, ObjectMapper json, LlmProvider llm, NliProvider nli) {
        this.tx = tx;
        this.json = json;
        this.llm = llm;
        this.nli = nli;
    }

    /** The judge behind a metric: the model for correctness and relevance; the NLI model together with it for faithfulness (claims). */
    public String judgeModel(String metric) {
        return metric.equals(Metrics.FAITHFULNESS) ? llm.model() + " + " + nli.model() + " (verifier " + ClaimVerifier.VERSION + ")" : llm.model();
    }

    /** calibrated: enough labels and kappa at the trusted level. uncalibrated: measured, but short of that. unknown: nothing measured yet. */
    public static String status(int n, Double kappa) {
        if (n == 0 || kappa == null) return "unknown";
        return n >= MIN_LABELS && kappa >= Cohen.TRUSTED_KAPPA ? "calibrated" : "uncalibrated";
    }

    private static void checkMetric(String metric) {
        if (!LABELS.containsKey(metric)) throw ApiException.badRequest("metric must be one of " + LABELS.keySet());
    }

    // ------------------------------------------------------------------ sampling

    /**
     * Picks items the judge has graded and a human has not labeled yet, balanced across the judge's verdicts so that rare verdicts are
     * represented (a random sample of mostly "supported" claims would say nothing about the judge's "unsupported"). The judge's verdict is
     * stored but never sent.
     */
    public Sample sample(Principal p, UUID ws, String metric, Integer size, UUID experimentId) {
        checkMetric(metric);
        int want = size == null ? DEFAULT_SAMPLE : size;
        if (want < 5 || want > 100) throw ApiException.badRequest("size must be between 5 and 100");
        String model = judgeModel(metric);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            register(j, ws, metric, model, experimentId);

            record Pending(UUID id, String key, String judgeLabel, UUID resultId) {}
            List<Pending> pending = j.query("SELECT id, item_key, judge_label, run_result_id FROM judge_labels WHERE metric = ? AND judge_model = ? AND rubric_version = ? AND human_label IS NULL "
                            + "ORDER BY created_at, item_key",
                    (rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getString("item_key"), rs.getString("judge_label"), rs.getObject("run_result_id", UUID.class)),
                    metric, model, Judge.RUBRIC_VERSION);

            Map<String, List<Pending>> byVerdict = new LinkedHashMap<>();
            for (Pending item : pending) byVerdict.computeIfAbsent(item.judgeLabel(), k -> new ArrayList<>()).add(item);
            Random random = new Random(7);
            for (List<Pending> group : byVerdict.values()) Collections.shuffle(group, random);
            List<Pending> chosen = new ArrayList<>();
            for (int round = 0; chosen.size() < want; round++) {
                boolean any = false;
                for (List<Pending> group : byVerdict.values()) {
                    if (round < group.size() && chosen.size() < want) {
                        chosen.add(group.get(round));
                        any = true;
                    }
                }
                if (!any) break;
            }
            Collections.shuffle(chosen, random); // the order must not reveal the stratum either

            List<Item> items = new ArrayList<>();
            for (Pending item : chosen) items.add(describe(j, metric, item.id(), item.key(), item.resultId()));
            return new Sample(metric, items.size(), pending.size() - items.size(), MIN_LABELS, items);
        });
    }

    /** Records every judged item of this metric from the finished full runs as a row to label, once. */
    private void register(JdbcTemplate j, UUID ws, String metric, String model, UUID experimentId) {
        var rows = j.queryForList("SELECT r.id, r.claims::text AS claims FROM run_results r JOIN runs u ON u.id = r.run_id JOIN experiments e ON e.id = u.experiment_id "
                + "WHERE e.mode = 'FULL' AND r.claims IS NOT NULL AND (?::uuid IS NULL OR e.id = ?::uuid) ORDER BY r.created_at DESC LIMIT 1000", experimentId, experimentId);
        for (var row : rows) {
            UUID resultId = (UUID) row.get("id");
            JsonNode claims = readTree((String) row.get("claims"));
            // an item graded by an older procedure or rubric belongs to that version's calibration, not to this one
            String graded = metric.equals(Metrics.FAITHFULNESS) ? claims.path("verifier").asText("legacy") : claims.path("rubricVersion").asText("v1");
            if (!graded.equals(metric.equals(Metrics.FAITHFULNESS) ? ClaimVerifier.VERSION : Judge.RUBRIC_VERSION)) continue;
            if (metric.equals(Metrics.FAITHFULNESS)) {
                int i = 0;
                for (JsonNode c : claims.path("claims")) {
                    String verdict = c.path("verdict").asText("").toLowerCase();
                    if (!verdict.isEmpty()) insert(j, ws, resultId, metric, "claim:" + i, model, verdict);
                    i++;
                }
            } else {
                String verdict = claims.path("judge").path(metric).path("verdict").asText("");
                if (!verdict.isEmpty()) insert(j, ws, resultId, metric, "answer", model, verdict);
            }
        }
    }

    private void insert(JdbcTemplate j, UUID ws, UUID resultId, String metric, String key, String model, String judgeLabel) {
        j.update("INSERT INTO judge_labels (workspace_id, run_result_id, metric, item_key, judge_model, rubric_version, judge_label) VALUES (?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (run_result_id, metric, item_key, judge_model, rubric_version) DO NOTHING", ws, resultId, metric, key, model, Judge.RUBRIC_VERSION, judgeLabel);
    }

    private Item describe(JdbcTemplate j, String metric, UUID labelId, String itemKey, UUID resultId) {
        var row = j.queryForMap("SELECT r.answer, r.claims::text AS claims, r.final_context::text AS context, q.question, q.expected_answer "
                + "FROM run_results r JOIN test_questions q ON q.id = r.question_id WHERE r.id = ?", resultId);
        String question = (String) row.get("question"), answer = (String) row.get("answer");
        List<String> options = LABELS.get(metric);
        if (metric.equals(Metrics.FAITHFULNESS)) {
            int index = Integer.parseInt(itemKey.substring("claim:".length()));
            JsonNode claim = readTree((String) row.get("claims")).path("claims").path(index);
            String evidence = chunkText(j, readTree((String) row.get("context")), claim.path("premiseLabel").asText(""));
            return new Item(labelId, metric, itemKey, question, null, answer, claim.path("text").asText(), evidence, options);
        }
        return new Item(labelId, metric, itemKey, question, metric.equals(Metrics.CORRECTNESS) ? (String) row.get("expected_answer") : null, answer, null, null, options);
    }

    private String chunkText(JdbcTemplate j, JsonNode context, String label) {
        for (JsonNode c : context) {
            if (c.path("label").asText().equals(label)) {
                var rows = j.queryForList("SELECT text FROM chunks WHERE id = ?::uuid", String.class, c.path("chunkId").asText());
                if (!rows.isEmpty()) return rows.get(0).length() > 1500 ? rows.get(0).substring(0, 1500) + "..." : rows.get(0);
            }
        }
        return "(the passage this statement was checked against is no longer available)";
    }

    // ------------------------------------------------------------------ labels and summary

    public List<MetricSummary> label(Principal p, UUID ws, List<HumanLabel> labels) {
        if (labels == null || labels.isEmpty()) throw ApiException.badRequest("No labels were sent");
        if (labels.size() > 200) throw ApiException.badRequest("At most 200 labels per request");
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            Set<String> touched = new java.util.LinkedHashSet<>();
            for (HumanLabel l : labels) {
                var rows = j.queryForList("SELECT metric, judge_model, rubric_version FROM judge_labels WHERE id = ?", l.labelId());
                if (rows.isEmpty()) throw ApiException.notFound("No such item to label");
                String metric = (String) rows.get(0).get("metric");
                String human = l.humanLabel() == null ? "" : l.humanLabel().strip().toLowerCase();
                if (!LABELS.get(metric).contains(human)) throw ApiException.badRequest("For " + metric + " the label must be one of " + LABELS.get(metric));
                j.update("UPDATE judge_labels SET human_label = ?, labeled_by = ?, labeled_at = now() WHERE id = ?", human, p.userId(), l.labelId());
                touched.add(metric + "|" + rows.get(0).get("judge_model") + "|" + rows.get(0).get("rubric_version"));
            }
            List<MetricSummary> out = new ArrayList<>();
            for (String key : touched) {
                String[] parts = key.split("\\|", 3);
                out.add(recompute(j, ws, parts[0], parts[1], parts[2]));
            }
            audit(j, ws, p.actor(), "calibration.label", "labels:" + labels.size());
            return out;
        });
    }

    /** Agreement of this judge with the human labels so far, saved so every experiment page can show it. */
    private MetricSummary recompute(JdbcTemplate j, UUID ws, String metric, String model, String rubric) {
        List<String> human = new ArrayList<>(), judge = new ArrayList<>();
        j.query("SELECT human_label, judge_label FROM judge_labels WHERE metric = ? AND judge_model = ? AND rubric_version = ? AND human_label IS NOT NULL ORDER BY created_at, item_key",
                rs -> { human.add(rs.getString(1)); judge.add(rs.getString(2)); }, metric, model, rubric);
        Cohen.Result r = Cohen.compare(human, judge);
        if (r.n() > 0) {
            j.update("INSERT INTO judge_calibrations (workspace_id, judge_model, rubric_version, metric, n, agreement, kappa, confusion) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
                            + "ON CONFLICT (workspace_id, judge_model, rubric_version, metric) DO UPDATE SET n = EXCLUDED.n, agreement = EXCLUDED.agreement, kappa = EXCLUDED.kappa, "
                            + "confusion = EXCLUDED.confusion, updated_at = now()", ws, model, rubric, metric, r.n(), r.agreement(), r.kappa(), write(confusion(r)));
        }
        return summary(j, metric, model, rubric, r, OffsetDateTime.now());
    }

    private Map<String, Object> confusion(Cohen.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("labels", r.labels());
        m.put("matrix", r.confusion());
        return m;
    }

    private MetricSummary summary(JdbcTemplate j, String metric, String model, String rubric, Cohen.Result r, OffsetDateTime updated) {
        int unlabeled = j.queryForObject("SELECT count(*) FROM judge_labels WHERE metric = ? AND judge_model = ? AND rubric_version = ? AND human_label IS NULL", Integer.class, metric, model, rubric);
        Double kappa = r.n() == 0 ? null : r.kappa();
        Map<String, Double> precision = new LinkedHashMap<>(), recall = new LinkedHashMap<>();
        r.precision().forEach((k, v) -> { if (!v.isNaN()) precision.put(k, v); });
        r.recall().forEach((k, v) -> { if (!v.isNaN()) recall.put(k, v); });
        return new MetricSummary(metric, model, rubric, r.n(), r.n() == 0 ? null : r.agreement(), kappa, status(r.n(), kappa), r.n() < MIN_LABELS, r.labels(), r.confusion(),
                precision, recall, unlabeled, updated);
    }

    public Summary summary(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            List<MetricSummary> metrics = new ArrayList<>();
            for (String metric : List.of(Metrics.FAITHFULNESS, Metrics.CORRECTNESS, Metrics.RELEVANCE)) {
                String model = judgeModel(metric);
                List<String> human = new ArrayList<>(), judge = new ArrayList<>();
                j.query("SELECT human_label, judge_label FROM judge_labels WHERE metric = ? AND judge_model = ? AND rubric_version = ? AND human_label IS NOT NULL ORDER BY created_at, item_key",
                        rs -> { human.add(rs.getString(1)); judge.add(rs.getString(2)); }, metric, model, Judge.RUBRIC_VERSION);
                var updated = j.queryForList("SELECT updated_at FROM judge_calibrations WHERE judge_model = ? AND rubric_version = ? AND metric = ?", OffsetDateTime.class, model, Judge.RUBRIC_VERSION, metric);
                metrics.add(summary(j, metric, model, Judge.RUBRIC_VERSION, Cohen.compare(human, judge), updated.isEmpty() ? null : updated.get(0)));
            }
            List<String> warnings = List.of("The model that writes the answers (" + llm.model() + ") also judges them. Models tend to favour their own output; calibration shows how far to trust it.");
            return new Summary(llm.model(), Judge.RUBRIC_VERSION, MIN_LABELS, Cohen.TRUSTED_KAPPA, metrics, warnings);
        });
    }

    /** Status of each judged metric for the experiment pages. */
    Map<String, ExperimentModel.JudgeMetric> statuses(JdbcTemplate j) {
        Map<String, ExperimentModel.JudgeMetric> out = new HashMap<>();
        for (String metric : LABELS.keySet()) {
            var rows = j.query("SELECT kappa, n FROM judge_calibrations WHERE judge_model = ? AND rubric_version = ? AND metric = ?",
                    (rs, n) -> new ExperimentModel.JudgeMetric(status(rs.getInt("n"), rs.getDouble("kappa")), rs.getDouble("kappa"), rs.getInt("n")),
                    judgeModel(metric), Judge.RUBRIC_VERSION, metric);
            out.put(metric, rows.isEmpty() ? new ExperimentModel.JudgeMetric("unknown", null, null) : rows.get(0));
        }
        return out;
    }

    private JsonNode readTree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored value is unreadable", e);
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
