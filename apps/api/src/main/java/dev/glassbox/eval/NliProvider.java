package dev.glassbox.eval;

import java.util.List;

/** Natural-language inference behind one interface so the sidecar model and test fakes are interchangeable (SPEC 3). */
public interface NliProvider {
    record Pair(String premise, String hypothesis) {}

    /** Probabilities that the premise entails, is neutral to, or contradicts the hypothesis; they sum to 1. */
    record Score(double entailment, double neutral, double contradiction) {}

    String model();

    /** One score per pair, in order. */
    List<Score> score(List<Pair> pairs);
}
