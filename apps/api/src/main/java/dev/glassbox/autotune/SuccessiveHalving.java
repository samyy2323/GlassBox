package dev.glassbox.autotune;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Successive halving (SPEC 9): every candidate starts on a quarter of the questions, the top third goes on to twice as many, and so on
 * until the survivors have been scored on all of them. Most of the work is spent on the candidates that are still in the race, and a
 * candidate that is clearly behind is dropped after a few cheap questions.
 *
 * Pure: the scores come from a callback, which is expected to keep the per-question scores it already has (the questions of a round are the
 * first {@code sample} of one fixed order, so each round only has to score the new ones). That also makes a crashed search resumable: running it
 * again asks for scores that are already stored.
 */
public final class SuccessiveHalving {
    public static final double START_FRACTION = 0.25;
    public static final double KEEP_FRACTION = 1.0 / 3.0;

    /** One step: how many questions, who entered, who went on (best first). */
    public record Round(int sample, List<String> entered, List<String> kept) {}

    /** {@code ranking} holds the survivors, best first, all scored on every question. */
    public record Result(List<String> ranking, List<Round> rounds) {}

    private SuccessiveHalving() {}

    /**
     * @param ids          the candidates
     * @param questions    how many questions there are
     * @param minSample    the first round never uses fewer questions than this (or all of them, if there are fewer)
     * @param minSurvivors the search never cuts below this many candidates before the last round, so enough finalists are left
     * @param meanScore    (candidate, number of questions) to the mean score on the first that many questions; higher is better
     * @param tieBreak     decides between equal scores; candidates that compare equal keep their input order
     */
    public static Result run(List<String> ids, int questions, int minSample, int minSurvivors, BiFunction<String, Integer, Double> meanScore, Comparator<String> tieBreak) {
        if (ids.isEmpty() || questions <= 0) return new Result(List.of(), List.of());
        List<Round> rounds = new ArrayList<>();
        List<String> alive = new ArrayList<>(ids);
        int size = Math.min(questions, Math.max(Math.min(minSample, questions), (int) Math.ceil(START_FRACTION * questions)));
        while (true) {
            Map<String, Double> score = new java.util.HashMap<>();
            for (String id : alive) score.put(id, meanScore.apply(id, size));
            List<String> ranked = new ArrayList<>(alive);
            ranked.sort(Comparator.comparingDouble((String id) -> -score.get(id)).thenComparing(tieBreak));
            if (size >= questions) {
                rounds.add(new Round(size, List.copyOf(alive), List.copyOf(ranked)));
                return new Result(ranked, rounds);
            }
            int keep = Math.min(ranked.size(), Math.max(minSurvivors, (int) Math.ceil(ranked.size() * KEEP_FRACTION)));
            List<String> kept = new ArrayList<>(ranked.subList(0, Math.max(1, keep)));
            rounds.add(new Round(size, List.copyOf(alive), List.copyOf(kept)));
            alive = kept;
            size = Math.min(questions, size * 2);
        }
    }
}
