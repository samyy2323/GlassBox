package dev.glassbox.stats;

/** "How small a difference could this test set detect?" (SPEC 8 power hint). Normal approximations, 95% confidence, 80% power. */
public final class Power {
    private static final double Z_ALPHA = 1.959964; // two-sided 5%
    private static final double Z_POWER = 0.841621; // 80% power

    private Power() {}

    /** Half-width of the 95% interval of one pass rate near {@code rate} measured on {@code n} questions: 1.96 * sqrt(p (1 - p) / n). */
    public static double rateHalfWidth(int n, double rate) {
        if (n <= 0) return Double.NaN;
        return Z_ALPHA * Math.sqrt(rate * (1 - rate) / n);
    }

    /**
     * Smallest mean difference a paired comparison on these questions could reliably detect, from how much the per-question
     * differences actually vary: (z_alpha + z_power) * sd / sqrt(n). NaN with fewer than two questions.
     */
    public static double minDetectableFromDiffs(double[] diffs) {
        if (diffs.length < 2) return Double.NaN;
        return (Z_ALPHA + Z_POWER) * Summary.sd(diffs) / Math.sqrt(diffs.length);
    }

    /** Before any run exists: the smallest detectable gap between two pass rates near {@code rate}, with independent runs (a worst case). */
    public static double minDetectableForRate(int n, double rate) {
        if (n <= 0) return Double.NaN;
        return (Z_ALPHA + Z_POWER) * Math.sqrt(2 * rate * (1 - rate) / n);
    }
}
