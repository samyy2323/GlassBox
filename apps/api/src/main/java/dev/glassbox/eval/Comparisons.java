package dev.glassbox.eval;

import dev.glassbox.stats.Bootstrap;
import dev.glassbox.stats.Holm;
import dev.glassbox.stats.PairedTests;
import dev.glassbox.stats.Power;
import dev.glassbox.stats.Summary;
import dev.glassbox.stats.Verdicts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paired comparison of runs on the same questions (SPEC 8): per-question differences, a bootstrap interval on their mean,
 * a significance test (exact McNemar for pass/fail metrics, sign-flip permutation for the rest), Holm correction across the
 * configurations compared against the same baseline, and the verdict sentence. Pure: it works on score vectors, not on the database.
 */
public final class Comparisons {
    public record Row(String metric, double a, double b, double delta, double ciLow, double ciHigh, double p, double pAdjusted,
                      String test, Verdicts.Outcome outcome, String verdict, double minDetectable, int n) {}

    /** Two runs to compare. {@code key} names the pair in the result and seeds its random numbers. */
    public record Pair(String key, Map<String, double[]> a, Map<String, double[]> b) {}

    private Comparisons() {}

    public static List<Row> compare(Map<String, double[]> a, Map<String, double[]> b, String seedKey) {
        return compareFamily(List.of(new Pair(seedKey, a, b))).get(seedKey);
    }

    /**
     * Compares every pair and corrects the p-values of each metric across the pairs (Holm), so that trying many configurations against
     * one baseline does not manufacture "significant" results.
     */
    public static Map<String, List<Row>> compareFamily(List<Pair> pairs) {
        record Draft(String key, String metric, double[] a, double[] b, double[] diffs, Bootstrap.Interval ci, double p, String test) {}
        List<Draft> drafts = new ArrayList<>();
        for (Pair pair : pairs) {
            List<String> metrics = new ArrayList<>();
            for (String m : Metrics.ALL) if (pair.a().containsKey(m) && pair.b().containsKey(m)) metrics.add(m);
            for (String m : pair.a().keySet()) if (pair.b().containsKey(m) && !metrics.contains(m)) metrics.add(m);
            for (String metric : metrics) {
                double[] x = pair.a().get(metric), y = pair.b().get(metric);
                double[] d = Summary.diff(y, x);
                long seed = pair.key().hashCode() * 31L + metric.hashCode();
                boolean binary = Metrics.BINARY.contains(metric);
                double p = binary ? PairedTests.mcnemarExactP(x, y) : PairedTests.signFlipP(d, seed);
                drafts.add(new Draft(pair.key(), metric, x, y, d, Bootstrap.meanCi(d, seed), p, binary ? "mcnemar" : "permutation"));
            }
        }
        // Holm corrects across configurations, one metric at a time (SPEC 8: "when many configs are compared"). The seven metrics of one
        // comparison are not independent questions: they describe the same retrieval, so correcting across them would only hide real gains.
        double[] adjusted = new double[drafts.size()];
        Map<String, List<Integer>> byMetric = new LinkedHashMap<>();
        for (int i = 0; i < drafts.size(); i++) byMetric.computeIfAbsent(drafts.get(i).metric(), k -> new ArrayList<>()).add(i);
        for (List<Integer> group : byMetric.values()) {
            double[] fixed = Holm.adjust(group.stream().mapToDouble(i -> drafts.get(i).p()).toArray());
            for (int k = 0; k < group.size(); k++) adjusted[group.get(k)] = fixed[k];
        }

        Map<String, List<Row>> out = new LinkedHashMap<>();
        for (Pair pair : pairs) out.put(pair.key(), new ArrayList<>());
        for (int i = 0; i < drafts.size(); i++) {
            Draft d = drafts.get(i);
            Verdicts.Verdict verdict = Verdicts.compare(d.ci().mean(), d.ci().low(), d.ci().high(), adjusted[i], 100, " points");
            out.get(d.key()).add(new Row(d.metric(), Summary.mean(d.a()), Summary.mean(d.b()), d.ci().mean(), d.ci().low(), d.ci().high(), d.p(), adjusted[i],
                    d.test(), verdict.outcome(), verdict.text(), Power.minDetectableFromDiffs(d.diffs()), d.diffs().length));
        }
        return out;
    }
}
