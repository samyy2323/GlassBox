package dev.glassbox.stats;

import java.util.Arrays;
import java.util.Comparator;

/** Holm-Bonferroni step-down correction for comparing many configurations at once (SPEC 8). */
public final class Holm {
    private Holm() {}

    /** Adjusted p-values in the order of the input: sort ascending, multiply the i-th smallest by (m - i), keep them non-decreasing, cap at 1. */
    public static double[] adjust(double[] p) {
        int m = p.length;
        Integer[] order = new Integer[m];
        for (int i = 0; i < m; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> p[i]));
        double[] adjusted = new double[m];
        double running = 0;
        for (int rank = 0; rank < m; rank++) {
            double value = Math.min(1.0, (m - rank) * p[order[rank]]);
            running = Math.max(running, value);
            adjusted[order[rank]] = running;
        }
        return adjusted;
    }
}
