package dev.glassbox.reports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.glassbox.autotune.AutotuneModel;
import dev.glassbox.eval.ExperimentModel.ExperimentView;
import dev.glassbox.eval.ExperimentModel.MetricView;
import dev.glassbox.eval.ExperimentModel.RunView;
import dev.glassbox.eval.Metrics;
import dev.glassbox.retrieval.PipelineConfig;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Freezes the result of an experiment or an AutoTune run into the snapshot that a report (PDF or share link) shows. The snapshot holds numbers, intervals,
 * verdict sentences, configurations, a failure histogram, test set statistics and the limitations that apply. It never holds document text or chat
 * content; with {@code redact} it also leaves out the text of individual questions, which is what could identify a source.
 */
@Component
public class ReportBuilder {
    public static final int VERSION = 1;

    private final ObjectMapper json;

    public ReportBuilder(ObjectMapper json) {
        this.json = json;
    }

    /** What the experiment report needs beyond the view: the failure labels per run (label to stage to count) and the failed questions per run. */
    public record Failures(Map<String, Map<String, Integer>> byStage, List<Map<String, String>> questions) {}

    public record TestsetFacts(String name, int version, int questions, int accepted, int edited, int rejected, Double acceptanceRate, Map<String, Integer> byType, int fromFeedback) {}

    public ObjectNode experiment(ExperimentView e, TestsetFacts set, Failures failures, boolean redact, OffsetDateTime now) {
        ObjectNode root = base("EXPERIMENT", "Experiment: " + e.name(), redact, now);
        ArrayNode summary = root.putArray("summary");
        summary.add(e.questionCount() + " questions from " + e.testsetName() + " v" + e.testsetVersion() + " were measured on " + e.runs().size() + " configuration" + (e.runs().size() == 1 ? "" : "s")
                + " (" + (e.mode().equals("FULL") ? "full answers, with claim checks and a judge" : "retrieval only, no model was asked to answer") + ").");
        String headline = e.mode().equals("FULL") ? Metrics.CORRECTNESS : "context_pass";
        e.vsBaseline().forEach(c -> c.rows().stream().filter(r -> r.metric().equals(headline)).findFirst()
                .ifPresent(r -> summary.add(c.label() + " against " + e.runs().get(0).label() + " on " + label(headline) + ": " + r.verdict())));
        if (e.runs().size() < 2) summary.add("There is one configuration, so nothing is compared.");

        root.set("testset", testset(set));
        ArrayNode configs = root.putArray("configs");
        for (RunView r : e.runs()) configs.addObject().put("label", r.label()).set("settings", settings(r.config()));

        ArrayNode metrics = root.putArray("metrics");
        for (RunView r : e.runs()) {
            ObjectNode run = metrics.addObject().put("label", r.label());
            ArrayNode rows = run.putArray("rows");
            for (MetricView m : r.metrics()) {
                if (!m.slice().equals("all") || !m.method().equals("question") || m.value() == null) continue;
                ObjectNode row = rows.addObject().put("metric", m.metric()).put("name", label(m.metric())).put("n", m.n()).put("judged", judged(m.metric()));
                row.put("value", m.value());
                if (m.ciLow() != null && m.ciHigh() != null) row.put("ciLow", m.ciLow()).put("ciHigh", m.ciHigh());
            }
        }

        ArrayNode comparisons = root.putArray("comparisons");
        e.vsBaseline().forEach(c -> {
            ObjectNode one = comparisons.addObject().put("label", c.label());
            ArrayNode rows = one.putArray("rows");
            c.rows().forEach(r -> rows.addObject().put("metric", r.metric()).put("name", label(r.metric())).put("delta", r.delta()).put("ciLow", r.ciLow()).put("ciHigh", r.ciHigh())
                    .put("pAdjusted", r.pAdjusted()).put("verdict", r.verdict()).put("n", r.n()).put("test", r.test()));
        });

        ObjectNode hist = root.putObject("failures");
        failures.byStage().forEach((run, stages) -> {
            ObjectNode o = hist.putObject(run);
            stages.forEach(o::put);
        });
        if (!redact) {
            ArrayNode q = root.putArray("failedQuestions");
            failures.questions().forEach(m -> q.addObject().put("run", m.get("run")).put("question", m.get("question")).put("stage", m.get("stage")));
        }

        ArrayNode lim = root.putArray("limitations");
        limitationsOfExperiment(e, lim);
        return root;
    }

    public ObjectNode autotune(AutotuneModel.View a, TestsetFacts set, boolean redact, OffsetDateTime now) {
        ObjectNode root = base("AUTOTUNE", "AutoTune: " + a.name(), redact, now);
        JsonNode result = a.result();
        ArrayNode summary = root.putArray("summary");
        summary.add("AutoTune searched chunking, embedding model and retrieval settings on " + a.split().get("tuning") + " tuning questions and confirmed the finalists and the baseline on "
                + a.split().get("heldOut") + " held-out questions that no choice was based on.");
        if (result != null) {
            JsonNode h = result.path("headline");
            summary.add(h.path("winnerLabel").asText("") + ": " + h.path("verdict").asText(""));
            root.set("headline", h);
            root.set("candidates", stripConfigs(result.path("candidates")));
            root.set("recommendations", result.path("recommendations"));
            root.set("effects", result.path("effects"));
            ObjectNode pareto = root.putObject("pareto");
            ArrayNode points = pareto.putArray("points");
            for (JsonNode c : result.path("candidates")) {
                points.addObject().put("label", c.path("label").asText()).put("quality", c.path("heldOut").path("quality").asDouble()).put("cost", c.path("heldOut").path("promptTokens").asDouble())
                        .put("latencyMs", c.path("heldOut").path("latencyMs").asDouble()).put("frontier", c.path("onFrontier").asBoolean()).put("role", c.path("role").asText());
            }
        }
        root.set("testset", testset(set));
        ArrayNode lim = root.putArray("limitations");
        lim.add("Quality is " + (result == null ? "" : result.path("headline").path("qualityDefinition").asText("")) + ".");
        lim.add("The held-out part is small on a small test set, so only a large difference can be told from noise: " + a.split().get("heldOutAnswerable") + " answerable held-out questions.");
        lim.add("The search space leaves out the generation model; the baseline is fixed 512-token chunks, the small embedding model, vector search and top 5.");
        if (a.stoppedBy() != null) lim.add("The search was cut short by its spend cap, so fewer configurations were compared than planned.");
        if (a.options().fullStage()) lim.add("Answer scores rest on a language model that judges its own answers and may not be calibrated.");
        return root;
    }

    // ------------------------------------------------------------------ pieces

    private ObjectNode base(String kind, String title, boolean redact, OffsetDateTime now) {
        ObjectNode root = json.createObjectNode();
        root.put("version", VERSION).put("kind", kind).put("title", title).put("generatedAt", now.toString()).put("redacted", redact);
        return root;
    }

    private ObjectNode testset(TestsetFacts t) {
        ObjectNode o = json.createObjectNode().put("name", t.name()).put("version", t.version()).put("questions", t.questions()).put("accepted", t.accepted()).put("edited", t.edited())
                .put("rejected", t.rejected()).put("fromFeedback", t.fromFeedback());
        if (t.acceptanceRate() != null) o.put("acceptanceRate", t.acceptanceRate());
        o.set("byType", json.valueToTree(t.byType()));
        return o;
    }

    /** Configurations without the stored JSON: the settings in words are enough for a reader. */
    private JsonNode stripConfigs(JsonNode candidates) {
        ArrayNode out = json.createArrayNode();
        for (JsonNode c : candidates) {
            ObjectNode copy = ((ObjectNode) c.deepCopy());
            copy.remove("config");
            copy.remove("heldOutRunId");
            copy.remove("tuningRunId");
            out.add(copy);
        }
        return out;
    }

    static ArrayNode settings(PipelineConfig c) {
        ArrayNode a = new ObjectMapper().createArrayNode();
        a.add(c.chunking() == null ? "default chunks (recursive, 512 tokens)" : c.chunking().label() + " tokens");
        a.add(c.retrieval().model());
        a.add(c.retrieval().mode().name().toLowerCase() + " search" + (c.retrieval().mode().name().equals("HYBRID") ? " " + c.retrieval().wVec() + ":" + c.retrieval().wKw() : ""));
        a.add(c.retrieval().kFused() + " candidates");
        a.add(c.reranker().name().equals("NONE") ? "no reranker" : "cross-encoder reranker");
        a.add("top " + c.topN());
        a.add(c.tokenBudget() + "-token context budget");
        if (c.transformer() != null) a.add("query transformer " + c.transformer().name().toLowerCase().replace('_', '-'));
        return a;
    }

    private void limitationsOfExperiment(ExperimentView e, ArrayNode lim) {
        lim.add("Every interval is a 95% bootstrap over the questions measured; a small test set gives wide intervals, and a difference counts as real only when its interval excludes zero and the corrected p-value is below 0.05.");
        lim.add("Gold evidence is exact text in the documents, but the questions were written by a language model and reviewed by a person; their quality limits what the numbers mean.");
        if (e.staleCount() > 0) lim.add(e.staleCount() + " question(s) were left out because their document changed after the test set was written.");
        if (e.judge() != null) {
            e.judge().metrics().forEach((metric, m) -> {
                if (m == null || !"calibrated".equals(m.status())) lim.add(label(metric) + " rests on a language model judge that is " + (m == null ? "not calibrated" : m.status()) + ".");
            });
            e.judge().warnings().forEach(lim::add);
        }
    }

    static boolean judged(String metric) {
        return List.of("faithfulness", "correctness", "relevance", "citation_precision").contains(metric);
    }

    static String label(String metric) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("recall@5", "Recall@5");
        names.put("recall@10", "Recall@10");
        names.put("mrr", "MRR");
        names.put("ndcg@10", "nDCG@10");
        names.put("context_recall", "Context recall");
        names.put("pass@5", "Evidence in top 5");
        names.put("context_pass", "Evidence in prompt");
        names.put("faithfulness", "Faithfulness");
        names.put("correctness", "Correctness");
        names.put("relevance", "Relevance");
        names.put("citation_precision", "Citation precision");
        names.put("false_abstention", "False abstention");
        names.put("abstention_accuracy", "Abstention accuracy");
        names.put("abstention_precision", "Abstention precision");
        names.put("abstention_f1", "Abstention F1");
        names.put("latency_p50_ms", "Latency p50 (ms)");
        names.put("latency_p95_ms", "Latency p95 (ms)");
        return names.getOrDefault(metric, metric);
    }

    /** Failure labels per run, built from (run label, stage) pairs. */
    public static Failures failures(List<String[]> labelledStages, List<String[]> questions) {
        Map<String, Map<String, Integer>> by = new LinkedHashMap<>();
        for (String[] p : labelledStages) by.computeIfAbsent(p[0], k -> new LinkedHashMap<>()).merge(p[1], 1, Integer::sum);
        List<Map<String, String>> qs = new ArrayList<>();
        for (String[] q : questions) qs.add(Map.of("run", q[0], "question", q[1], "stage", q[2]));
        return new Failures(by, qs);
    }
}
