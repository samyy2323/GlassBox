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

    /** One measured question: its type, the document its evidence comes from (the cluster), its scores and how long retrieval took. */
    public record Score(String qtype, int cluster, Map<String, Double> metrics, long latencyMs) {}

    public record Row(String metric, String slice, String method, double value, double ciLow, double ciHigh, int n) {}

    private RunAggregates() {}

    public static List<Row> compute(List<Score> scores, long seed) {
        List<Row> rows = new ArrayList<>();
        Map<String, List<Score>> byType = new LinkedHashMap<>();
        for (Score s : scores) byType.computeIfAbsent(s.qtype(), k -> new ArrayList<>()).add(s);

        for (String metric : RetrievalMetrics.ALL) {
            rows.add(row(metric, ALL, QUESTION, scores, metric, seed));
            if (scores.size() > 1) {
                double[] x = vector(scores, metric);
                int[] groups = scores.stream().mapToInt(Score::cluster).toArray();
                Bootstrap.Interval ci = Bootstrap.clusterMeanCi(x, groups, Bootstrap.RESAMPLES, Bootstrap.CONFIDENCE, seed + metric.hashCode());
                rows.add(new Row(metric, ALL, CLUSTER, ci.mean(), ci.low(), ci.high(), x.length));
            }
            for (var e : byType.entrySet()) rows.add(row(metric, e.getKey(), QUESTION, e.getValue(), metric, seed));
        }
        double[] latency = scores.stream().mapToDouble(s -> s.latencyMs()).toArray();
        if (latency.length > 0) {
            rows.add(new Row(LATENCY_P50, ALL, QUESTION, Summary.percentile(latency, 50), Double.NaN, Double.NaN, latency.length));
            rows.add(new Row(LATENCY_P95, ALL, QUESTION, Summary.percentile(latency, 95), Double.NaN, Double.NaN, latency.length));
        }
        return rows;
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
