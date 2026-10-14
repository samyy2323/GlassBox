package dev.glassbox.stats;

import java.util.Locale;

/** The plain-language verdict of SPEC 8: a difference is only "real" when the interval excludes zero and the (corrected) p-value is below alpha. */
public final class Verdicts {
    public static final double ALPHA = 0.05;

    public enum Outcome { B_BETTER, A_BETTER, NO_DIFFERENCE }

    public record Verdict(Outcome outcome, String text) {}

    private Verdicts() {}

    /**
     * @param delta mean of (B minus A), in the metric's own unit
     * @param scale multiplier for display: 100 turns a 0 to 1 rate into "points"
     * @param unit  what follows the number, e.g. " points" or " ms"
     */
    public static Verdict compare(double delta, double ciLow, double ciHigh, double p, double scale, String unit) {
        boolean excludesZero = ciLow > 0 || ciHigh < 0;
        boolean significant = excludesZero && p < ALPHA;
        String ci = "95% CI [" + signed(ciLow * scale) + ", " + signed(ciHigh * scale) + "]";
        if (!significant) return new Verdict(Outcome.NO_DIFFERENCE, "No significant difference (" + ci.replace("95% ", "") + ", p=" + p(p) + ")");
        boolean bWins = delta > 0;
        return new Verdict(bWins ? Outcome.B_BETTER : Outcome.A_BETTER,
                (bWins ? "B beats A by " : "A beats B by ") + signed(Math.abs(delta) * scale) + unit + ", " + ci + ", p=" + p(p));
    }

    static String signed(double v) {
        double rounded = Math.round(v * 10.0) / 10.0;
        if (rounded == 0) rounded = 0; // avoid "-0.0"
        return String.format(Locale.ROOT, "%+.1f", rounded);
    }

    static String p(double p) {
        return p < 0.001 ? "<0.001" : String.format(Locale.ROOT, "%.2f", p);
    }
}
