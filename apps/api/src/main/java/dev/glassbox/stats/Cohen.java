package dev.glassbox.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Agreement between a judge and a human labeller (SPEC 8 calibration): percent agreement, Cohen's kappa, confusion matrix, per-verdict precision and recall. */
public final class Cohen {
    /** kappa below this and the judge's metrics are shown as uncalibrated. */
    public static final double TRUSTED_KAPPA = 0.6;

    /**
     * @param labels    the verdicts seen, in a fixed order; rows and columns of {@code confusion} follow it
     * @param confusion confusion[h][j] = questions the human labelled {@code labels[h]} and the judge {@code labels[j]}
     * @param kappa     1 when there is only one label and everyone agrees (kappa is 0/0 there)
     */
    public record Result(int n, double agreement, double kappa, List<String> labels, int[][] confusion,
                         Map<String, Double> precision, Map<String, Double> recall) {
        public boolean trusted() {
            return n > 0 && kappa >= TRUSTED_KAPPA;
        }
    }

    private Cohen() {}

    public static Result compare(List<String> human, List<String> judge) {
        if (human.size() != judge.size()) throw new IllegalArgumentException("one human label per judge verdict");
        int n = human.size();
        List<String> labels = new ArrayList<>(new TreeSet<>(concat(human, judge)));
        int k = labels.size();
        int[][] m = new int[k][k];
        for (int i = 0; i < n; i++) m[labels.indexOf(human.get(i))][labels.indexOf(judge.get(i))]++;

        int agree = 0;
        for (int i = 0; i < k; i++) agree += m[i][i];
        double po = n == 0 ? 0 : (double) agree / n;
        double pe = 0;
        for (int i = 0; i < k; i++) {
            double rowShare = 0, colShare = 0;
            for (int j = 0; j < k; j++) {
                rowShare += m[i][j];
                colShare += m[j][i];
            }
            pe += (rowShare / n) * (colShare / n);
        }
        double kappa = n == 0 ? 0 : pe >= 1.0 ? (po >= 1.0 ? 1.0 : 0.0) : (po - pe) / (1 - pe);

        // The judge is the thing being measured: precision = of what it said, how much a human agreed; recall = of what a human said, how much it found.
        Map<String, Double> precision = new LinkedHashMap<>(), recall = new LinkedHashMap<>();
        for (int i = 0; i < k; i++) {
            int judged = 0, labelled = 0;
            for (int j = 0; j < k; j++) {
                judged += m[j][i];
                labelled += m[i][j];
            }
            precision.put(labels.get(i), judged == 0 ? Double.NaN : (double) m[i][i] / judged);
            recall.put(labels.get(i), labelled == 0 ? Double.NaN : (double) m[i][i] / labelled);
        }
        return new Result(n, po, kappa, labels, m, precision, recall);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
