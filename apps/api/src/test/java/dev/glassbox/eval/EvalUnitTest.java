package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.glassbox.eval.RetrievalMetrics.Gold;
import dev.glassbox.eval.RetrievalMetrics.Ref;
import dev.glassbox.stats.Verdicts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure logic of the eval lab: the hit definition, the metrics, the aggregates and the paired comparison. */
class EvalUnitTest {
    static final UUID DOC = new UUID(0, 1), OTHER = new UUID(0, 2);

    static Ref ref(int start, int end) {
        return new Ref(DOC, start, end);
    }

    static Gold gold(int start, int end) {
        return new Gold(DOC, start, end);
    }

    // ------------------------------------------------------------------ hit definition

    @Test
    void aChunkHitsASpanWhenItCoversAtLeastHalfOfIt() {
        Gold span = gold(100, 200);
        assertThat(RetrievalMetrics.hits(ref(90, 150), span)).as("exactly 50 of 100 characters").isTrue();
        assertThat(RetrievalMetrics.hits(ref(90, 149), span)).as("49 of 100").isFalse();
        assertThat(RetrievalMetrics.hits(ref(0, 1000), span)).as("a big chunk around it").isTrue();
        assertThat(RetrievalMetrics.hits(ref(120, 140), span)).as("a small chunk inside covers only 20%").isFalse();
        assertThat(RetrievalMetrics.hits(ref(200, 300), span)).as("touching is not overlapping").isFalse();
        assertThat(RetrievalMetrics.hits(new Ref(OTHER, 100, 200), span)).as("same offsets, other document").isFalse();
        assertThat(RetrievalMetrics.hits(ref(0, 500), gold(50, 50))).as("an empty span can never be hit").isFalse();
    }

    // ------------------------------------------------------------------ metrics by hand

    @Test
    void recallCountsSpansCoveredByTheTopK() {
        List<Gold> gold = List.of(gold(0, 100), gold(500, 600));
        List<Ref> ranked = List.of(ref(0, 60), ref(1000, 1100), ref(550, 600));
        assertThat(RetrievalMetrics.recallAtK(ranked, gold, 1)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.recallAtK(ranked, gold, 2)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.recallAtK(ranked, gold, 3)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.recallAtK(List.of(), gold, 5)).isZero();
        assertThat(RetrievalMetrics.recallAtK(ranked, List.of(), 5)).as("no gold: not measurable").isNaN();
    }

    @Test
    void aMultiHopQuestionIsFullyRecalledOnlyWhenEverySpanIsHit() {
        List<Gold> gold = List.of(gold(0, 100), gold(500, 600));
        Map<String, Double> half = RetrievalMetrics.compute(List.of(ref(0, 100)), List.of(ref(0, 100)), gold);
        assertThat(half.get(RetrievalMetrics.CONTEXT_RECALL)).isEqualTo(0.5);
        assertThat(half.get(RetrievalMetrics.CONTEXT_PASS)).isZero();
        Map<String, Double> both = RetrievalMetrics.compute(List.of(ref(0, 100), ref(500, 600)), List.of(ref(0, 100), ref(500, 600)), gold);
        assertThat(both.get(RetrievalMetrics.CONTEXT_PASS)).isEqualTo(1.0);
        assertThat(both.get(RetrievalMetrics.PASS_5)).isEqualTo(1.0);
    }

    @Test
    void reciprocalRankIsOneOverThePositionOfTheFirstHit() {
        List<Gold> gold = List.of(gold(100, 200));
        assertThat(RetrievalMetrics.reciprocalRank(List.of(ref(100, 200)), gold)).isEqualTo(1.0);
        assertThat(RetrievalMetrics.reciprocalRank(List.of(ref(0, 10), ref(100, 200)), gold)).isEqualTo(0.5);
        assertThat(RetrievalMetrics.reciprocalRank(List.of(ref(0, 10), ref(20, 30), ref(0, 400)), gold)).isCloseTo(1.0 / 3, within(1e-12));
        assertThat(RetrievalMetrics.reciprocalRank(List.of(ref(0, 10)), gold)).isZero();
    }

    @Test
    void ndcgUsesBinaryRelevanceAndAnIdealListOfOneChunkPerSpan() {
        List<Gold> one = List.of(gold(100, 200));
        assertThat(RetrievalMetrics.ndcgAtK(List.of(ref(0, 10), ref(100, 200)), one, 10)).isCloseTo(1.0 / (Math.log(3) / Math.log(2)), within(1e-12));
        assertThat(RetrievalMetrics.ndcgAtK(List.of(ref(100, 200)), one, 10)).isEqualTo(1.0);

        List<Gold> two = List.of(gold(0, 100), gold(500, 600));
        assertThat(RetrievalMetrics.ndcgAtK(List.of(ref(0, 100), ref(500, 600)), two, 10)).isCloseTo(1.0, within(1e-12));
        double dcg = 1 + 1 / (Math.log(4) / Math.log(2)), ideal = 1 + 1 / (Math.log(3) / Math.log(2));
        assertThat(RetrievalMetrics.ndcgAtK(List.of(ref(0, 100), ref(5000, 5100), ref(500, 600)), two, 10)).isCloseTo(dcg / ideal, within(1e-12));
        assertThat(RetrievalMetrics.ndcgAtK(List.of(ref(0, 100), ref(500, 600)), two, 1)).as("k = 1 can reach only one span").isCloseTo(1.0, within(1e-12));
    }

    @Test
    void rankedAndContextAreScoredSeparately() {
        List<Gold> gold = List.of(gold(100, 200));
        // retrieved at rank 7 (so not in the top 5) and dropped from the prompt: recall@10 sees it, context recall does not
        List<Ref> ranked = new ArrayList<>();
        for (int i = 0; i < 6; i++) ranked.add(ref(1000 + i * 10, 1005 + i * 10));
        ranked.add(ref(100, 200));
        Map<String, Double> m = RetrievalMetrics.compute(ranked, List.of(ref(1000, 1005)), gold);
        assertThat(m.get(RetrievalMetrics.RECALL_5)).isZero();
        assertThat(m.get(RetrievalMetrics.RECALL_10)).isEqualTo(1.0);
        assertThat(m.get(RetrievalMetrics.CONTEXT_RECALL)).isZero();
        assertThat(m.get(RetrievalMetrics.MRR)).isCloseTo(1.0 / 7, within(1e-12));
        assertThat(m.keySet()).containsExactlyElementsOf(RetrievalMetrics.ALL);
    }

    // ------------------------------------------------------------------ aggregates

    static RunAggregates.Score score(String type, int cluster, double pass, long latency) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String metric : RetrievalMetrics.ALL) m.put(metric, pass);
        return new RunAggregates.Score(type, cluster, m, latency);
    }

    @Test
    void aggregatesCoverEveryMetricSliceClusterAndLatency() {
        List<RunAggregates.Score> scores = new ArrayList<>();
        for (int i = 0; i < 20; i++) scores.add(score(i % 2 == 0 ? "FACTUAL" : "NUMERIC", i % 4, i < 15 ? 1 : 0, 10 + i));
        List<RunAggregates.Row> rows = RunAggregates.compute(scores, 1);

        RunAggregates.Row overall = rows.stream().filter(r -> r.metric().equals("context_pass") && r.slice().equals("all") && r.method().equals("question")).findFirst().orElseThrow();
        assertThat(overall.value()).isEqualTo(0.75);
        assertThat(overall.n()).isEqualTo(20);
        assertThat(overall.ciLow()).isLessThan(0.75);
        assertThat(overall.ciHigh()).isGreaterThan(0.75);
        assertThat(rows.stream().filter(r -> r.metric().equals("context_pass") && r.method().equals("cluster"))).hasSize(1);
        assertThat(rows.stream().filter(r -> r.metric().equals("mrr") && r.slice().equals("FACTUAL")).findFirst().orElseThrow().n()).isEqualTo(10);
        RunAggregates.Row p50 = rows.stream().filter(r -> r.metric().equals(RunAggregates.LATENCY_P50)).findFirst().orElseThrow();
        assertThat(p50.value()).isCloseTo(19.5, within(1e-9));
        assertThat(rows.stream().filter(r -> r.metric().equals(RunAggregates.LATENCY_P95)).findFirst().orElseThrow().value()).isGreaterThan(p50.value());
    }

    @Test
    void aSingleQuestionRunStillAggregates() {
        List<RunAggregates.Row> rows = RunAggregates.compute(List.of(score("FACTUAL", 0, 1, 5)), 1);
        assertThat(rows).isNotEmpty();
        assertThat(rows.stream().noneMatch(r -> r.method().equals("cluster"))).as("nothing to resample").isTrue();
    }

    // ------------------------------------------------------------------ comparison

    static Map<String, double[]> vectors(double[] pass) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (String metric : RetrievalMetrics.ALL) m.put(metric, pass.clone());
        return m;
    }

    static double[] passes(int n, int firstPass) {
        double[] x = new double[n];
        for (int i = 0; i < firstPass; i++) x[i] = 1;
        return x;
    }

    @Test
    void aClearImprovementIsReportedWithAnIntervalAndAVerdict() {
        // A passes 20 of 40; B passes the same 20 and 8 more: 8 questions flipped fail -> pass, none the other way
        List<Comparisons.Row> rows = Comparisons.compare(vectors(passes(40, 20)), vectors(passes(40, 28)), "a:b");
        Comparisons.Row r = rows.stream().filter(x -> x.metric().equals("context_pass")).findFirst().orElseThrow();
        assertThat(r.delta()).isCloseTo(0.2, within(1e-12));
        assertThat(r.test()).isEqualTo("mcnemar");
        assertThat(r.p()).isCloseTo(2.0 / 256, within(1e-12));
        assertThat(r.ciLow()).isGreaterThan(0.0);
        assertThat(r.outcome()).isEqualTo(Verdicts.Outcome.B_BETTER);
        assertThat(r.verdict()).startsWith("B beats A by +20.0 points, 95% CI [");
        assertThat(r.pAdjusted()).as("one pair, one metric: nothing to correct across").isEqualTo(r.p());
        assertThat(rows.stream().filter(x -> !RetrievalMetrics.BINARY.contains(x.metric())).allMatch(x -> x.test().equals("permutation"))).isTrue();
    }

    @Test
    void identicalRunsAreNotDifferent() {
        List<Comparisons.Row> rows = Comparisons.compare(vectors(passes(30, 20)), vectors(passes(30, 20)), "a:a");
        assertThat(rows).hasSize(RetrievalMetrics.ALL.size());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.delta()).isZero();
            assertThat(r.p()).isEqualTo(1.0);
            assertThat(r.outcome()).isEqualTo(Verdicts.Outcome.NO_DIFFERENCE);
        });
    }

    @Test
    void aSmallGapOnAFewQuestionsIsNotCalledReal() {
        // 3 of 10 questions flipped in B's favour: the exact test cannot rule out luck
        Comparisons.Row r = Comparisons.compare(vectors(passes(10, 5)), vectors(passes(10, 8)), "x").stream().filter(x -> x.metric().equals("context_pass")).findFirst().orElseThrow();
        assertThat(r.delta()).isCloseTo(0.3, within(1e-12));
        assertThat(r.outcome()).isEqualTo(Verdicts.Outcome.NO_DIFFERENCE);
        assertThat(r.verdict()).startsWith("No significant difference (CI [");
    }

    @Test
    void theOtherDirectionIsNamedToo() {
        Comparisons.Row r = Comparisons.compare(vectors(passes(40, 28)), vectors(passes(40, 20)), "b:a").stream().filter(x -> x.metric().equals("context_pass")).findFirst().orElseThrow();
        assertThat(r.outcome()).isEqualTo(Verdicts.Outcome.A_BETTER);
        assertThat(r.verdict()).startsWith("A beats B by +20.0 points");
    }

    @Test
    void holmCorrectsAcrossAWholeFamilyOfComparisons() {
        Map<String, double[]> base = vectors(passes(40, 20));
        List<Comparisons.Pair> pairs = new ArrayList<>();
        for (int i = 0; i < 4; i++) pairs.add(new Comparisons.Pair("run" + i, base, vectors(passes(40, 20 + 3 * i))));
        Map<String, List<Comparisons.Row>> family = Comparisons.compareFamily(pairs);
        Comparisons.Row alone = Comparisons.compare(base, vectors(passes(40, 26)), "run2").stream().filter(x -> x.metric().equals("context_pass")).findFirst().orElseThrow();
        Comparisons.Row inFamily = family.get("run2").stream().filter(x -> x.metric().equals("context_pass")).findFirst().orElseThrow();
        assertThat(inFamily.p()).as("the raw p-value does not depend on the family").isCloseTo(alone.p(), within(1e-12));
        assertThat(inFamily.pAdjusted()).as("but the corrected one is stricter for a bigger family").isGreaterThan(alone.pAdjusted());
        assertThat(family.keySet()).containsExactly("run0", "run1", "run2", "run3");
    }

    @Test
    void theSevenMetricsOfOneComparisonAreNotCorrectedAgainstEachOther() {
        // identical vectors for every metric would give a Holm-over-metrics p of 7 * 0.0078 = 0.055 and hide a clear gain
        List<Comparisons.Row> rows = Comparisons.compare(vectors(passes(40, 20)), vectors(passes(40, 28)), "a:b");
        assertThat(rows).allSatisfy(r -> assertThat(r.pAdjusted()).isEqualTo(r.p()));
        assertThat(rows.stream().filter(r -> r.outcome() == Verdicts.Outcome.B_BETTER)).hasSize(RetrievalMetrics.ALL.size());
    }

    @Test
    void emptyInputGivesNoRows() {
        assertThat(Comparisons.compare(new LinkedHashMap<>(), new LinkedHashMap<>(), "none")).isEmpty();
    }
}
