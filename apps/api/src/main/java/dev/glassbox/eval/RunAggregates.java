package dev.glassbox.eval;

import dev.glassbox.stats.Bootstrap;
import dev.glassbox.stats.Summary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the per-question scores of a run into the numbers shown for it: each metric overall with a bootstrap interval, sliced by
 * question type, with a cluster bootstrap by source document next to the overall figure, and latency percentiles.
 */
public final class RunAggregates {
    public static final String ALL = "all";
    public static final String QUESTION = "question";
    public static final String CLUSTER = "cluster";
    public static final String LATENCY_P50 = "latency_p50_ms";
    public static final String LATENCY_P95 = "latency_p95_ms";
    /** Of everything the system refused, the share that really was unanswerable. Run level: a ratio of counts, so no interval. */
    public static final String ABSTENTION_PRECISION = "abstention_precision";
    public static final String ABSTENTION_F1 = "abstention_f1";

    /** One measured question: its type, the document its evidence comes from (the cluster), its scores and how long retrieval took. */
    public record Score(String qtype, int cluster, Map<String, Double> metrics, long latencyMs) {}

    public record Row(String metric, String slice, String method, double value, double ciLow, double ciHigh, int n) {}

    private RunAggregates() {}

    public static List<Row> compute(List<Score> scores, long seed) {
        List<Row> rows = new ArrayList<>();
        Map<String, List<Score>> byType = new LinkedHashMap<>();
        for (Score s : scores) byType.computeIfAbsent(s.qtype(), k -> new ArrayList<>()).add(s);

        for (String metric : Metrics.ALL) {
            List<Score> with = scores.stream().filter(s -> s.metrics().containsKey(metric)).toList();
            if (with.isEmpty()) continue; // a metric no question could be scored on (faithfulness in a retrieval-only run)
            rows.add(row(metric, ALL, QUESTION, with, metric, seed));
            if (with.size() > 1) {
                double[] x = vector(with, metric);
                int[] groups = with.stream().mapToInt(Score::cluster).toArray();
                Bootstrap.Interval ci = Bootstrap.clusterMeanCi(x, groups, Bootstrap.RESAMPLES, Bootstrap.CONFIDENCE, seed + metric.hashCode());
                rows.add(new Row(metric, ALL, CLUSTER, ci.mean(), ci.low(), ci.high(), x.length));
            }
            for (var e : byType.entrySet()) {
                List<Score> slice = e.getValue().stream().filter(s -> s.metrics().containsKey(metric)).toList();
                if (!slice.isEmpty()) rows.add(row(metric, e.getKey(), QUESTION, slice, metric, seed));
            }
        }
        double[] latency = scores.stream().mapToDouble(s -> s.latencyMs()).toArray();
        if (latency.length > 0) {
            rows.add(new Row(LATENCY_P50, ALL, QUESTION, Summary.percentile(latency, 50), Double.NaN, Double.NaN, latency.length));
            rows.add(new Row(LATENCY_P95, ALL, QUESTION, Summary.percentile(latency, 95), Double.NaN, Double.NaN, latency.length));
        }
        abstention(scores, rows);
        return rows;
    }

    /**
     * Precision and F1 of abstaining. Recall is {@code abstention_accuracy} (the share of unanswerable questions refused). A refusal is either a
     * correct one (on an unanswerable question) or a false one (on an answerable question), so precision needs both counts.
     */
    static void abstention(List<Score> scores, List<Row> rows) {
        double correct = 0, wrong = 0;
        int unanswerable = 0, n = 0;
        for (Score s : scores) {
            Double acc = s.metrics().get(Metrics.ABSTENTION_ACCURACY), fa = s.metrics().get(Metrics.FALSE_ABSTENTION);
            if (acc != null) { correct += acc; unanswerable++; n++; }
            if (fa != null) { wrong += fa; n++; }
        }
        if (unanswerable == 0 || correct + wrong == 0) return; // nothing was refused, or nothing could be: precision is undefined, not zero
        double precision = correct / (correct + wrong), recall = correct / unanswerable;
        rows.add(new Row(ABSTENTION_PRECISION, ALL, QUESTION, precision, Double.NaN, Double.NaN, n));
        if (precision + recall > 0) rows.add(new Row(ABSTENTION_F1, ALL, QUESTION, 2 * precision * recall / (precision + recall), Double.NaN, Double.NaN, n));
    }

    private static Row row(String name, String slice, String method, List<Score> scores, String metric, long seed) {
        double[] x = vector(scores, metric);
        Bootstrap.Interval ci = Bootstrap.meanCi(x, seed + metric.hashCode());
        return new Row(name, slice, method, ci.mean(), ci.low(), ci.high(), x.length);
    }

    private static double[] vector(List<Score> scores, String metric) {
        return scores.stream().mapToDouble(s -> s.metrics().get(metric)).toArray();
    }
}
