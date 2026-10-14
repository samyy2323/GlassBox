package dev.glassbox.stats;

import java.util.SplittableRandom;

/** Significance tests for paired comparisons (SPEC 8). */
public final class PairedTests {
    /** Up to this many questions the sign-flip test enumerates all 2^n flips; beyond it, it samples. */
    public static final int EXACT_LIMIT = 20;
    public static final int MONTE_CARLO = 100_000;

    private PairedTests() {}

    /**
     * Paired permutation (sign-flip) test on the per-question differences, two-sided, for the mean difference. Under "no difference"
     * each question's sign is arbitrary, so the observed mean is compared with the means of all sign assignments.
     * Exact for at most {@link #EXACT_LIMIT} questions, Monte Carlo with the usual +1 correction above that.
     */
    public static double signFlipP(double[] diffs, long seed) {
        int n = diffs.length;
        if (n == 0) return 1.0;
        double observed = Math.abs(sum(diffs));
        double tolerance = 1e-12 * Math.max(1.0, observed);
        if (n <= EXACT_LIMIT) {
            long total = 1L << n, hits = 0;
            for (long mask = 0; mask < total; mask++) {
                double s = 0;
                for (int i = 0; i < n; i++) s += ((mask >> i) & 1L) == 0 ? diffs[i] : -diffs[i];
                if (Math.abs(s) >= observed - tolerance) hits++;
            }
            return (double) hits / total;
        }
        SplittableRandom rnd = new SplittableRandom(seed);
        long hits = 0;
        for (int r = 0; r < MONTE_CARLO; r++) {
            double s = 0;
            for (int i = 0; i < n; i++) s += rnd.nextBoolean() ? diffs[i] : -diffs[i];
            if (Math.abs(s) >= observed - tolerance) hits++;
        }
        return (hits + 1.0) / (MONTE_CARLO + 1.0);
    }

    /**
     * Exact McNemar test for paired pass/fail. Only the discordant questions matter: {@code b} questions that A passed and B
     * failed, {@code c} the other way round. Under "no difference" each discordant question is a fair coin, so the p-value is the
     * two-sided binomial tail. 1.0 when nothing is discordant.
     */
    public static double mcnemarExactP(int b, int c) {
        int n = b + c;
        if (n == 0) return 1.0;
        int k = Math.min(b, c);
        double tail = 0;
        for (int i = 0; i <= k; i++) tail += Math.exp(logChoose(n, i) - n * Math.log(2));
        return Math.min(1.0, 2 * tail);
    }

    /** McNemar from two pass/fail vectors (values above 0.5 count as pass). */
    public static double mcnemarExactP(double[] passA, double[] passB) {
        if (passA.length != passB.length) throw new IllegalArgumentException("paired vectors must have the same length");
        int b = 0, c = 0;
        for (int i = 0; i < passA.length; i++) {
            boolean a = passA[i] > 0.5, bb = passB[i] > 0.5;
            if (a && !bb) b++;
            if (!a && bb) c++;
        }
        return mcnemarExactP(b, c);
    }

    private static double sum(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s;
    }

    private static double logChoose(int n, int k) {
        double r = 0;
        for (int i = 1; i <= k; i++) r += Math.log(n - k + i) - Math.log(i);
        return r;
    }
}
