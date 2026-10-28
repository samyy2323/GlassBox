package dev.glassbox.ci;

import dev.glassbox.eval.Comparisons;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The rules of the CI gate (SPEC 14), worked out from numbers that already exist. Pure: no database, no clock.
 *
 * <ul>
 *   <li><b>minimum</b>: the metric of the candidate must be at least the given value. By default the measured value is compared; with {@code strict} the lower end of its
 *       95% interval is, so a pass means "confidently at least", not "at least on this sample".</li>
 *   <li><b>regression</b>: against a baseline run on the same questions, the gate fails only when the drop is both larger than the allowed drop AND statistically
 *       significant (the paired test of the Eval Lab, p below {@code alpha}). A drop that large but within noise does not block a merge, because noise would make the
 *       gate flaky, and a flaky gate is switched off.</li>
 * </ul>
 */
public final class GateEvaluator {
    private GateEvaluator() {}

    /** What the rules are; stored with the gate. Metric names are the Eval Lab's (for example {@code recall@5}); values and drops are fractions between 0 and 1. */
    public record Spec(String mode, UUID baselineRunId, Map<String, Double> failUnder, Map<String, Double> failOnRegression, boolean strict, double alpha) {
        public Spec {
            failUnder = failUnder == null ? Map.of() : new TreeMap<>(failUnder);
            failOnRegression = failOnRegression == null ? Map.of() : new TreeMap<>(failOnRegression);
        }
    }

    /** A measured value of the candidate with its 95% interval, as the Eval Lab reports it. */
    public record Stat(double value, Double ciLow, Double ciHigh, int n) {}

    /** One line of the verdict. {@code kind} is {@code minimum} or {@code regression}. */
    public record Check(String kind, String metric, boolean passed, String title, String reason, Double value, Double ciLow, Double ciHigh, Double threshold,
                        Double baseline, Double delta, Double p, Integer n) {}

    public static List<Check> evaluate(Spec spec, Map<String, Stat> candidate, Map<String, Comparisons.Row> comparison) {
        List<Check> out = new ArrayList<>();
        for (var e : spec.failUnder().entrySet()) out.add(minimum(spec, e.getKey(), e.getValue(), candidate.get(e.getKey())));
        for (var e : spec.failOnRegression().entrySet()) out.add(regression(spec, e.getKey(), e.getValue(), comparison == null ? null : comparison.get(e.getKey())));
        return out;
    }

    public static boolean passed(List<Check> checks) {
        return !checks.isEmpty() && checks.stream().allMatch(Check::passed);
    }

    private static Check minimum(Spec spec, String metric, double min, Stat s) {
        String title = metric + " is at least " + pct(min) + (spec.strict() ? " (lower end of the interval)" : "");
        if (s == null) {
            return new Check("minimum", metric, false, title, "This run did not measure " + metric + ".", null, null, null, min, null, null, null, null);
        }
        double compared = spec.strict() && s.ciLow() != null ? s.ciLow() : s.value();
        boolean ok = compared >= min;
        String reason = (spec.strict() ? "The lower end of the interval is " : "The value is ") + pct(compared) + (ok ? ", which meets " : ", which is below ") + pct(min) + ".";
        return new Check("minimum", metric, ok, title, reason, s.value(), s.ciLow(), s.ciHigh(), min, null, null, null, s.n());
    }

    private static Check regression(Spec spec, String metric, double allowedDrop, Comparisons.Row row) {
        String title = metric + " does not drop by more than " + points(allowedDrop) + " against the baseline (significant at p<" + trim(spec.alpha()) + ")";
        if (row == null) {
            return new Check("regression", metric, false, title, "The baseline run and this run have no " + metric + " in common.", null, null, null, allowedDrop, null, null, null, null);
        }
        double drop = row.a() - row.b();
        boolean larger = drop > allowedDrop + 1e-12;
        boolean significant = row.pAdjusted() < spec.alpha();
        boolean fails = larger && significant;
        String reason;
        if (fails) {
            reason = "Dropped " + points(drop) + " (allowed " + points(allowedDrop) + "), and the drop is significant (p=" + p(row.pAdjusted()) + "): blocked.";
        } else if (larger) {
            reason = "Dropped " + points(drop) + ", more than the " + points(allowedDrop) + " allowed, but p=" + p(row.pAdjusted()) + " means it cannot be told from noise: not blocked.";
        } else if (drop > 0) {
            reason = "Dropped " + points(drop) + ", within the " + points(allowedDrop) + " allowed.";
        } else {
            reason = "No drop (" + signed(row.b() - row.a()) + ").";
        }
        return new Check("regression", metric, !fails, title, reason, row.b(), null, null, allowedDrop, row.a(), row.b() - row.a(), row.pAdjusted(), row.n());
    }

    // ---------------------------------------------------------------- number words

    static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }

    static String points(double v) {
        return String.format(Locale.ROOT, "%.1f points", v * 100);
    }

    static String signed(double v) {
        return String.format(Locale.ROOT, "%+.1f points", v * 100);
    }

    static String p(double v) {
        return v < 0.001 ? "<0.001" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
