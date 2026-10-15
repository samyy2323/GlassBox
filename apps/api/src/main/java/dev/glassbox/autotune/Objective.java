package dev.glassbox.autotune;

import java.util.Comparator;

/**
 * What the user wants AutoTune to optimise (SPEC 9). It decides how candidates are ordered when the search keeps "the best" ones.
 * <ul>
 *   <li>BEST_QUALITY: highest quality, cheaper first on a tie.</li>
 *   <li>CHEAPEST_ABOVE_FLOOR: among candidates at or above a quality floor, the cheapest; candidates under the floor come after, best quality first.</li>
 *   <li>FASTEST_WITHIN_CAP: among candidates whose latency is within the cap, the best quality (then the faster); candidates over the cap come after.</li>
 * </ul>
 */
public enum Objective {
    BEST_QUALITY, CHEAPEST_ABOVE_FLOOR, FASTEST_WITHIN_CAP;

    /** What an ordering needs to know about a candidate. */
    public record Score(String id, double quality, double cost, double latencyMs) {}

    /** Best candidate first. {@code floor} is a quality in [0,1], {@code latencyCapMs} a time; each is only read by its own objective. */
    public Comparator<Score> comparator(double floor, double latencyCapMs) {
        Comparator<Score> byQuality = Comparator.comparingDouble(Score::quality).reversed().thenComparingDouble(Score::cost).thenComparing(Score::id);
        return switch (this) {
            case BEST_QUALITY -> byQuality;
            case CHEAPEST_ABOVE_FLOOR -> Comparator.<Score>comparingInt(s -> s.quality() >= floor ? 0 : 1)
                    .thenComparing((a, b) -> a.quality() >= floor ? Comparator.comparingDouble(Score::cost).thenComparing(Comparator.comparingDouble(Score::quality).reversed())
                            .thenComparing(Score::id).compare(a, b) : byQuality.compare(a, b));
            case FASTEST_WITHIN_CAP -> Comparator.<Score>comparingInt(s -> s.latencyMs() <= latencyCapMs ? 0 : 1)
                    .thenComparing((a, b) -> a.latencyMs() <= latencyCapMs
                            ? Comparator.comparingDouble(Score::quality).reversed().thenComparingDouble(Score::latencyMs).thenComparing(Score::id).compare(a, b)
                            : Comparator.comparingDouble(Score::latencyMs).thenComparing(Score::id).compare(a, b));
        };
    }
}
