package dev.glassbox.autotune;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What AutoTune reports once the search is over: the Pareto frontier of quality against cost and latency, three named recommendations, and
 * a table of what each setting did on average. All pure.
 *
 * Cost is the prompt tokens a question needs (what a paid model would bill for the context), latency is the time to answer; both are lower
 * is better. A point is on the frontier when no other point is at least as good on all three and better on one.
 */
public final class Pareto {
    /** The "fastest" recommendation may give up this much quality (3 points) compared with the best. */
    public static final double FASTEST_TOLERANCE = 0.03;
    /** "Best value" only considers points with at least this share of the best quality, so a cheap useless configuration cannot win. */
    public static final double VALUE_QUALITY_SHARE = 0.90;

    public record Point(String id, double quality, double cost, double latencyMs) {}

    public record Picks(String bestQuality, String bestValue, String fastest) {}

    public record Effect(String dimension, String value, double averageQuality, int n) {}

    private Pareto() {}

    static boolean dominates(Point a, Point b) {
        boolean noWorse = a.quality() >= b.quality() && a.cost() <= b.cost() && a.latencyMs() <= b.latencyMs();
        boolean better = a.quality() > b.quality() || a.cost() < b.cost() || a.latencyMs() < b.latencyMs();
        return noWorse && better;
    }

    /** The non-dominated points, best quality first. Identical points are all kept. */
    public static List<Point> frontier(List<Point> points) {
        List<Point> out = new ArrayList<>();
        for (Point p : points) {
            boolean dominated = false;
            for (Point q : points) {
                if (q != p && dominates(q, p)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) out.add(p);
        }
        out.sort(Comparator.comparingDouble(Point::quality).reversed().thenComparingDouble(Point::cost).thenComparing(Point::id));
        return out;
    }

    public static Optional<Picks> recommend(List<Point> points) {
        if (points.isEmpty()) return Optional.empty();
        List<Point> front = frontier(points);
        Point best = front.get(0);
        Point value = front.stream().filter(p -> p.quality() >= VALUE_QUALITY_SHARE * best.quality())
                .max(Comparator.comparingDouble((Point p) -> p.quality() / Math.max(p.cost(), 1e-9)).thenComparing(Point::id, Comparator.reverseOrder()))
                .orElse(best);
        Point fastest = points.stream().filter(p -> p.quality() >= best.quality() - FASTEST_TOLERANCE)
                .min(Comparator.comparingDouble(Point::latencyMs).thenComparing(Comparator.comparingDouble(Point::quality).reversed()).thenComparing(Point::id))
                .orElse(best);
        return Optional.of(new Picks(best.id(), value.id(), fastest.id()));
    }

    /**
     * Average quality for every value of every dimension, so the user can see what mattered. Each candidate brings its settings
     * ({@code dimension -> value}) and its quality; the numbers are only comparable when the qualities were measured on the same questions,
     * which is the caller's job (AutoTune passes the first round's scores, which every candidate has).
     */
    public static List<Effect> effects(Map<String, Map<String, String>> settingsByCandidate, Map<String, Double> qualityByCandidate) {
        Map<String, Map<String, double[]>> acc = new LinkedHashMap<>();
        for (var e : settingsByCandidate.entrySet()) {
            Double q = qualityByCandidate.get(e.getKey());
            if (q == null || q.isNaN()) continue;
            for (var s : e.getValue().entrySet()) {
                double[] a = acc.computeIfAbsent(s.getKey(), k -> new LinkedHashMap<>()).computeIfAbsent(s.getValue(), k -> new double[2]);
                a[0] += q;
                a[1]++;
            }
        }
        List<Effect> out = new ArrayList<>();
        for (var d : acc.entrySet()) {
            // a dimension that never varied explains nothing
            if (d.getValue().size() < 2) continue;
            for (var v : d.getValue().entrySet()) out.add(new Effect(d.getKey(), v.getKey(), v.getValue()[0] / v.getValue()[1], (int) v.getValue()[1]));
        }
        return out;
    }
}
