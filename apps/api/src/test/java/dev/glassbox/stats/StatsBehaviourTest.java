package dev.glassbox.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Properties, edge cases and the verdict wording of the statistics package. */
class StatsBehaviourTest {

    static double[] passFail(int n, double rate, long seed) {
        Random r = new Random(seed);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = r.nextDouble() < rate ? 1 : 0;
        return x;
    }

    // ---------------------------------------------------------------- bootstrap

    @Test
    void theIntervalContainsTheMeanAndIsReproducibleForASeed() {
        double[] x = passFail(100, 0.8, 1);
        Bootstrap.Interval a = Bootstrap.meanCi(x, 42), b = Bootstrap.meanCi(x, 42);
        assertThat(a).isEqualTo(b);
        double[] continuous = new Random(2).doubles(60).toArray(); // 0/1 scores have so few distinct resample means that two seeds can agree
        assertThat(Bootstrap.meanCi(continuous, 43)).isNotEqualTo(Bootstrap.meanCi(continuous, 42));
        assertThat(a.low()).isLessThanOrEqualTo(a.mean());
        assertThat(a.high()).isGreaterThanOrEqualTo(a.mean());
    }

    @Test
    void itMatchesTheSpecsRuleOfThumbForAHundredQuestionsNearEightyPercent() {
        // SPEC 8: "a pass rate near 80% with 100 questions: one run's 95% interval is about plus or minus 8 points"
        double[] x = new double[100];
        Arrays.fill(x, 0, 80, 1.0);
        Bootstrap.Interval ci = Bootstrap.meanCi(x, 7);
        assertThat(ci.mean()).isEqualTo(0.8);
        assertThat(ci.mean() - ci.low()).isCloseTo(0.08, within(0.012));
        assertThat(ci.high() - ci.mean()).isCloseTo(0.08, within(0.012));
        assertThat(Power.rateHalfWidth(100, 0.8)).isCloseTo(0.0784, within(0.0005));
    }

    @Test
    void moreQuestionsMakeTheIntervalNarrower() {
        double narrow = width(Bootstrap.meanCi(passFail(400, 0.7, 3), 1)), wide = width(Bootstrap.meanCi(passFail(40, 0.7, 3), 1));
        assertThat(narrow).isLessThan(wide);
    }

    static double width(Bootstrap.Interval i) {
        return i.high() - i.low();
    }

    @Test
    void degenerateInputsGiveDegenerateButSaneIntervals() {
        assertThat(Bootstrap.meanCi(new double[0], 1).mean()).isNaN();
        assertThat(Bootstrap.meanCi(new double[] {0.5}, 1)).isEqualTo(new Bootstrap.Interval(0.5, 0.5, 0.5));
        double[] constant = new double[30];
        Arrays.fill(constant, 0.25);
        assertThat(Bootstrap.meanCi(constant, 1)).isEqualTo(new Bootstrap.Interval(0.25, 0.25, 0.25));
        assertThat(Bootstrap.clusterMeanCi(new double[] {1, 0}, new int[] {7, 7}, 1000, 0.95, 1)).as("one cluster: nothing to resample").isEqualTo(new Bootstrap.Interval(0.5, 0.5, 0.5));
    }

    @Test
    void clusterIntervalsAreWiderWhenQuestionsOfADocumentMoveTogether() {
        // 6 documents, 20 questions each; every question of a document has the same score, so the 120 questions are really 6 observations
        double[] x = new double[120];
        int[] groups = new int[120];
        double[] docScore = {0.9, 0.5, 0.8, 0.3, 0.7, 0.6};
        for (int i = 0; i < 120; i++) {
            groups[i] = i / 20;
            x[i] = docScore[i / 20];
        }
        double naive = width(Bootstrap.meanCi(x, 1)), cluster = width(Bootstrap.clusterMeanCi(x, groups, Bootstrap.RESAMPLES, Bootstrap.CONFIDENCE, 1));
        assertThat(cluster).as("the cluster bootstrap does not pretend to have 120 independent questions").isGreaterThan(naive * 2);
    }

    // ---------------------------------------------------------------- tests

    @Test
    void permutationPValuesAreProbabilitiesAndSymmetric() {
        double[] d = {0.2, -0.1, 0.4, 0.3, -0.2, 0.5, 0.1};
        double[] flipped = Arrays.stream(d).map(v -> -v).toArray();
        double p = PairedTests.signFlipP(d, 1);
        assertThat(p).isBetween(0.0, 1.0);
        assertThat(PairedTests.signFlipP(flipped, 1)).as("two-sided: B vs A and A vs B give the same p").isEqualTo(p);
    }

    @Test
    void permutationEdgeCases() {
        assertThat(PairedTests.signFlipP(new double[0], 1)).isEqualTo(1.0);
        assertThat(PairedTests.signFlipP(new double[] {0, 0, 0, 0}, 1)).as("no difference at all").isEqualTo(1.0);
        double[] allImproved = new double[10];
        Arrays.fill(allImproved, 0.3);
        assertThat(PairedTests.signFlipP(allImproved, 1)).as("2 of 2^10 flips are as extreme").isCloseTo(2.0 / 1024, within(1e-12));
    }

    @Test
    void permutationSwitchesToSamplingForBigSetsAndStillFindsRealEffects() {
        Random r = new Random(5);
        double[] real = new double[200], none = new double[200];
        for (int i = 0; i < 200; i++) {
            real[i] = 0.1 + 0.2 * r.nextGaussian();
            none[i] = 0.2 * r.nextGaussian();
        }
        assertThat(PairedTests.signFlipP(real, 1)).isLessThan(0.001);
        assertThat(PairedTests.signFlipP(none, 1)).isGreaterThan(0.05);
        assertThat(PairedTests.signFlipP(real, 9)).as("a Monte Carlo p-value never reaches exactly 0").isGreaterThan(0.0);
    }

    @Test
    void mcnemarOnlyLooksAtDiscordantQuestions() {
        assertThat(PairedTests.mcnemarExactP(0, 0)).isEqualTo(1.0);
        assertThat(PairedTests.mcnemarExactP(5, 5)).isEqualTo(1.0);
        assertThat(PairedTests.mcnemarExactP(0, 10)).isCloseTo(2.0 / 1024, within(1e-12));
        assertThat(PairedTests.mcnemarExactP(10, 0)).isEqualTo(PairedTests.mcnemarExactP(0, 10));
        assertThat(PairedTests.mcnemarExactP(new double[] {1, 1, 0, 0, 1}, new double[] {1, 1, 0, 0, 1})).as("identical runs").isEqualTo(1.0);
        assertThat(PairedTests.mcnemarExactP(3, 12)).isLessThan(PairedTests.mcnemarExactP(5, 10));
    }

    @Test
    void holmIsMonotoneCappedAndNeverSmallerThanTheRawValue() {
        double[] p = {0.01, 0.04, 0.03, 0.005, 0.9};
        double[] adj = Holm.adjust(p);
        for (int i = 0; i < p.length; i++) {
            assertThat(adj[i]).isGreaterThanOrEqualTo(p[i]);
            assertThat(adj[i]).isLessThanOrEqualTo(1.0);
        }
        assertThat(adj[3]).as("the smallest p is multiplied by m").isCloseTo(0.005 * 5, within(1e-12));
        assertThat(Holm.adjust(new double[0])).isEmpty();
        assertThat(Holm.adjust(new double[] {0.7})).containsExactly(0.7);
    }

    // ---------------------------------------------------------------- kappa

    @Test
    void cohenKappaOnAWorkedExample() {
        // 2x2 table [[20, 5], [10, 15]]: po = 35/50 = 0.7, pe = 0.5, kappa = 0.4
        List<String> human = new java.util.ArrayList<>(), judge = new java.util.ArrayList<>();
        add(human, judge, "yes", "yes", 20);
        add(human, judge, "yes", "no", 5);
        add(human, judge, "no", "yes", 10);
        add(human, judge, "no", "no", 15);
        Cohen.Result r = Cohen.compare(human, judge);
        assertThat(r.agreement()).isCloseTo(0.7, within(1e-12));
        assertThat(r.kappa()).isCloseTo(0.4, within(1e-12));
        assertThat(r.confusion()).isEqualTo(new int[][] {{15, 10}, {5, 20}}); // labels sorted: no, yes; rows human, columns judge
        assertThat(r.trusted()).isFalse();
    }

    static void add(List<String> human, List<String> judge, String h, String j, int times) {
        for (int i = 0; i < times; i++) {
            human.add(h);
            judge.add(j);
        }
    }

    @Test
    void kappaEdgeCases() {
        assertThat(Cohen.compare(List.of("a", "b", "a"), List.of("a", "b", "a")).kappa()).isEqualTo(1.0);
        assertThat(Cohen.compare(List.of("a", "a"), List.of("a", "a")).kappa()).as("one label, all agree").isEqualTo(1.0);
        assertThat(Cohen.compare(List.of(), List.of()).trusted()).isFalse();
        Cohen.Result chance = Cohen.compare(List.of("a", "a", "b", "b"), List.of("a", "b", "a", "b"));
        assertThat(chance.kappa()).as("agreement no better than chance").isCloseTo(0.0, within(1e-12));
        assertThat(Cohen.compare(List.of("a", "b"), List.of("a", "b")).trusted()).isTrue();
    }

    // ---------------------------------------------------------------- power and verdict wording

    @Test
    void powerHintGrowsWhenTheTestSetShrinks() {
        assertThat(Power.minDetectableForRate(25, 0.8)).isGreaterThan(Power.minDetectableForRate(100, 0.8));
        assertThat(Power.minDetectableFromDiffs(new double[] {0.1})).isNaN();
        assertThat(Power.minDetectableFromDiffs(new double[] {0.1, 0.1, 0.1})).as("no variation, nothing to resolve").isCloseTo(0.0, within(1e-12));
    }

    @Test
    void verdictTextFollowsTheSpecsTemplates() {
        Verdicts.Verdict win = Verdicts.compare(0.041, 0.008, 0.075, 0.02, 100, " points");
        assertThat(win.outcome()).isEqualTo(Verdicts.Outcome.B_BETTER);
        assertThat(win.text()).isEqualTo("B beats A by +4.1 points, 95% CI [+0.8, +7.5], p=0.02");

        Verdicts.Verdict loss = Verdicts.compare(-0.041, -0.075, -0.008, 0.02, 100, " points");
        assertThat(loss.outcome()).isEqualTo(Verdicts.Outcome.A_BETTER);
        assertThat(loss.text()).isEqualTo("A beats B by +4.1 points, 95% CI [-7.5, -0.8], p=0.02");

        Verdicts.Verdict none = Verdicts.compare(0.014, -0.021, 0.05, 0.4, 100, " points");
        assertThat(none.outcome()).isEqualTo(Verdicts.Outcome.NO_DIFFERENCE);
        assertThat(none.text()).startsWith("No significant difference (CI [-2.1, +5.0]");
    }

    @Test
    void aDifferenceNeedsBothTheIntervalAndTheTestToCountAsReal() {
        assertThat(Verdicts.compare(0.05, 0.01, 0.09, 0.2, 100, "").outcome()).as("interval excludes zero but p is large").isEqualTo(Verdicts.Outcome.NO_DIFFERENCE);
        assertThat(Verdicts.compare(0.05, -0.01, 0.11, 0.01, 100, "").outcome()).as("p is small but the interval includes zero").isEqualTo(Verdicts.Outcome.NO_DIFFERENCE);
        assertThat(Verdicts.compare(0.0, 0.0, 0.0, 1.0, 100, "").text()).doesNotContain("-0.0");
        assertThat(Verdicts.p(0.0004)).isEqualTo("<0.001");
    }

    @Test
    void summaryBasics() {
        assertThat(Summary.mean(new double[] {1, 2, 3})).isEqualTo(2.0);
        assertThat(Summary.mean(new double[0])).isNaN();
        assertThat(Summary.sd(new double[] {2, 4, 4, 4, 5, 5, 7, 9})).isCloseTo(2.138, within(0.001));
        assertThat(Summary.diff(new double[] {3, 5}, new double[] {1, 1})).containsExactly(2.0, 4.0);
    }
}
