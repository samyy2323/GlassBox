package dev.glassbox.stats;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Percentile bootstrap for the mean of per-question scores (SPEC 8): resample questions with replacement, take the mean of
 * each resample, and read the interval off the percentiles of those means. Seeded, so the same inputs always give the same interval.
 */
public final class Bootstrap {
    public static final int RESAMPLES = 10_000;
    public static final double CONFIDENCE = 0.95;

    public record Interval(double mean, double low, double high) {}

    private Bootstrap() {}

    public static Interval meanCi(double[] x, long seed) {
        return meanCi(x, RESAMPLES, CONFIDENCE, seed);
    }

    public static Interval meanCi(double[] x, int resamples, double confidence, long seed) {
        if (x.length == 0) return new Interval(Double.NaN, Double.NaN, Double.NaN);
        double mean = Summary.mean(x);
        if (x.length == 1) return new Interval(mean, mean, mean);
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] means = new double[resamples];
        int n = x.length;
        for (int b = 0; b < resamples; b++) {
            double sum = 0;
            for (int i = 0; i < n; i++) sum += x[rnd.nextInt(n)];
            means[b] = sum / n;
        }
        return interval(mean, means, confidence);
    }

    /**
     * Cluster bootstrap: questions from one document resemble each other, so whole clusters are resampled, not single questions.
     * {@code groups[i]} is the cluster of question i (any integers).
     */
    public static Interval clusterMeanCi(double[] x, int[] groups, int resamples, double confidence, long seed) {
        if (x.length != groups.length) throw new IllegalArgumentException("one group per value");
        if (x.length == 0) return new Interval(Double.NaN, Double.NaN, Double.NaN);
        int[] ids = Arrays.stream(groups).distinct().sorted().toArray();
        int k = ids.length;
        double[] sum = new double[k];
        int[] count = new int[k];
        for (int i = 0; i < x.length; i++) {
            int g = Arrays.binarySearch(ids, groups[i]);
            sum[g] += x[i];
            count[g]++;
        }
        double mean = Summary.mean(x);
        if (k == 1) return new Interval(mean, mean, mean);
        SplittableRandom rnd = new SplittableRandom(seed);
        double[] means = new double[resamples];
        for (int b = 0; b < resamples; b++) {
            double s = 0;
            long c = 0;
            for (int i = 0; i < k; i++) {
                int g = rnd.nextInt(k);
                s += sum[g];
                c += count[g];
            }
            means[b] = s / c;
        }
        return interval(mean, means, confidence);
    }

    private static Interval interval(double mean, double[] means, double confidence) {
        Arrays.sort(means);
        double alpha = (1 - confidence) / 2 * 100;
        return new Interval(mean, Summary.percentileSorted(means, alpha), Summary.percentileSorted(means, 100 - alpha));
    }
}
