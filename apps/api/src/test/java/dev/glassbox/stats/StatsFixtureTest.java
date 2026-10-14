package dev.glassbox.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Java statistics against reference values computed with SciPy / NumPy / scikit-learn (benchmarks/stats_fixtures.py). */
class StatsFixtureTest {
    static JsonNode fx;

    @BeforeAll
    static void load() throws IOException {
        try (InputStream in = StatsFixtureTest.class.getResourceAsStream("/stats-fixtures.json")) {
            fx = new ObjectMapper().readTree(in);
        }
    }

    static double[] vec(JsonNode a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).asDouble();
        return out;
    }

    static int[] ints(JsonNode a) {
        int[] out = new int[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).asInt();
        return out;
    }

    static List<String> strings(JsonNode a) {
        List<String> out = new ArrayList<>();
        a.forEach(n -> out.add(n.asText()));
        return out;
    }

    @Test
    void referenceFileCameFromRealScipy() {
        assertThat(fx.get("generatedWith").get("scipy").asText()).isNotBlank();
        assertThat(fx.get("mcnemar").size()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void mcnemarExactMatchesScipyBinomialTest() {
        for (JsonNode c : fx.get("mcnemar")) {
            assertThat(PairedTests.mcnemarExactP(c.get("b01").asInt(), c.get("c01").asInt())).isCloseTo(c.get("p").asDouble(), within(1e-9));
            if (!c.get("a").isNull()) {
                assertThat(PairedTests.mcnemarExactP(vec(c.get("a")), vec(c.get("b")))).isCloseTo(c.get("p").asDouble(), within(1e-9));
            }
        }
    }

    @Test
    void signFlipPermutationMatchesScipyPermutationTest() {
        for (JsonNode c : fx.get("permutation")) {
            assertThat(PairedTests.signFlipP(vec(c.get("diffs")), 1)).isCloseTo(c.get("p").asDouble(), within(1e-12));
        }
    }

    @Test
    void percentileBootstrapAgreesWithScipyWithinMonteCarloError() {
        long seed = 1;
        for (JsonNode c : fx.get("bootstrap")) {
            double[] x = vec(c.get("x"));
            Bootstrap.Interval ci = Bootstrap.meanCi(x, seed++);
            assertThat(ci.mean()).isCloseTo(c.get("mean").asDouble(), within(1e-12));
            // two independent 10,000-resample runs differ by about 0.002 for these inputs: 0.01 is generous, a wrong method is far off
            assertThat(ci.low()).isCloseTo(c.get("low").asDouble(), within(0.01));
            assertThat(ci.high()).isCloseTo(c.get("high").asDouble(), within(0.01));
        }
    }

    @Test
    void clusterBootstrapAgreesWithTheNumpyReference() {
        for (JsonNode c : fx.get("clusterBootstrap")) {
            Bootstrap.Interval ci = Bootstrap.clusterMeanCi(vec(c.get("x")), ints(c.get("groups")), Bootstrap.RESAMPLES, Bootstrap.CONFIDENCE, 5);
            assertThat(ci.mean()).isCloseTo(c.get("mean").asDouble(), within(1e-9));
            assertThat(ci.low()).isCloseTo(c.get("low").asDouble(), within(0.012));
            assertThat(ci.high()).isCloseTo(c.get("high").asDouble(), within(0.012));
        }
    }

    @Test
    void holmMatchesTheReferenceImplementation() {
        for (JsonNode c : fx.get("holm")) {
            double[] adjusted = Holm.adjust(vec(c.get("p")));
            double[] expected = vec(c.get("adjusted"));
            for (int i = 0; i < expected.length; i++) assertThat(adjusted[i]).isCloseTo(expected[i], within(1e-12));
        }
    }

    @Test
    void cohenKappaAgreementAndPerVerdictScoresMatchScikitLearn() {
        for (JsonNode c : fx.get("kappa")) {
            Cohen.Result r = Cohen.compare(strings(c.get("human")), strings(c.get("judge")));
            assertThat(r.labels()).isEqualTo(strings(c.get("labels")));
            assertThat(r.agreement()).isCloseTo(c.get("agreement").asDouble(), within(1e-12));
            assertThat(r.kappa()).isCloseTo(c.get("kappa").asDouble(), within(1e-12));
            for (String label : r.labels()) {
                assertThat(r.precision().get(label)).isCloseTo(c.get("precision").get(label).asDouble(), within(1e-12));
                assertThat(r.recall().get(label)).isCloseTo(c.get("recall").get(label).asDouble(), within(1e-12));
            }
        }
    }

    @Test
    void percentilesMatchNumpy() {
        for (JsonNode c : fx.get("percentile")) {
            double[] x = vec(c.get("x"));
            assertThat(Summary.percentile(x, 50)).isCloseTo(c.get("p50").asDouble(), within(1e-9));
            assertThat(Summary.percentile(x, 95)).isCloseTo(c.get("p95").asDouble(), within(1e-9));
            assertThat(Summary.percentile(x, 2.5)).isCloseTo(c.get("p2_5").asDouble(), within(1e-9));
        }
    }

    @Test
    void powerHintsMatchTheNormalApproximation() {
        for (JsonNode c : fx.get("power").get("cases")) {
            assertThat(Power.rateHalfWidth(c.get("n").asInt(), c.get("rate").asDouble())).isCloseTo(c.get("halfWidth").asDouble(), within(1e-5));
            assertThat(Power.minDetectableForRate(c.get("n").asInt(), c.get("rate").asDouble())).isCloseTo(c.get("minDetectable").asDouble(), within(1e-5));
        }
        assertThat(Power.minDetectableFromDiffs(vec(fx.get("power").get("diffs")))).isCloseTo(fx.get("power").get("minDetectableFromDiffs").asDouble(), within(1e-5));
    }
}
