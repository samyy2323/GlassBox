package dev.glassbox.stats;

import java.util.Arrays;

/** Descriptive statistics. No framework dependencies: this package is plain Java so it can be checked against SciPy. */
public final class Summary {
    private Summary() {}

    public static double mean(double[] x) {
        if (x.length == 0) return Double.NaN;
        double sum = 0;
        for (double v : x) sum += v;
        return sum / x.length;
    }

    /** Sample standard deviation (n - 1 in the denominator); NaN for fewer than two values. */
    public static double sd(double[] x) {
        if (x.length < 2) return Double.NaN;
        double m = mean(x), ss = 0;
        for (double v : x) ss += (v - m) * (v - m);
        return Math.sqrt(ss / (x.length - 1));
    }

    /** The p-th percentile (0 to 100) with linear interpolation, the same definition as numpy.percentile's default. */
    public static double percentile(double[] values, double p) {
        if (values.length == 0) return Double.NaN;
        double[] s = values.clone();
        Arrays.sort(s);
        return percentileSorted(s, p);
    }

    static double percentileSorted(double[] sorted, double p) {
        if (sorted.length == 1) return sorted[0];
        double rank = (p / 100.0) * (sorted.length - 1);
        int lo = (int) Math.floor(rank);
        int hi = Math.min(lo + 1, sorted.length - 1);
        return sorted[lo] + (rank - lo) * (sorted[hi] - sorted[lo]);
    }

    public static double[] diff(double[] b, double[] a) {
        if (a.length != b.length) throw new IllegalArgumentException("paired vectors must have the same length");
        double[] d = new double[a.length];
        for (int i = 0; i < d.length; i++) d[i] = b[i] - a[i];
        return d;
    }
}
