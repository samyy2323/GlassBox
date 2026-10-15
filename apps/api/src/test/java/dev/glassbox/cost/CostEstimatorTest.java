package dev.glassbox.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.glassbox.cost.CostEstimator.Breakdown;
import dev.glassbox.cost.CostEstimator.Price;
import dev.glassbox.cost.CostEstimator.Usage;
import org.junit.jupiter.api.Test;

class CostEstimatorTest {
    private static final Usage USAGE = new Usage(
            1_000_000,                  // embeddings
            2_000_000, 500_000, 100,    // generation: 100 answers
            800_000, 100_000, 400,      // judging
            200_000, 50_000, 100,       // query rewriting
            0, 0, 0);

    @Test
    void everyLineIsTokensTimesPriceAndTheTotalIsTheirSum() {
        Breakdown b = CostEstimator.price(USAGE, new Price(2.0, 8.0), 0.1, 0.0);
        assertThat(b.embeddings().usd()).isCloseTo(0.1, within(1e-12));                       // 1M tokens at $0.10 per million
        assertThat(b.generation().usd()).isCloseTo(2.0 * 2 + 8.0 * 0.5, within(1e-12));       // 8.0
        assertThat(b.judging().usd()).isCloseTo(2.0 * 0.8 + 8.0 * 0.1, within(1e-12));        // 2.4
        assertThat(b.rewriting().usd()).isCloseTo(2.0 * 0.2 + 8.0 * 0.05, within(1e-12));     // 0.8
        assertThat(b.synthesis().usd()).isZero();
        assertThat(b.usd()).isCloseTo(0.1 + 8.0 + 2.4 + 0.8, within(1e-12));
        assertThat(b.tokens()).isEqualTo(1_000_000 + 2_500_000 + 900_000 + 250_000);
        assertThat(b.calls()).isEqualTo(100 + 400 + 100);
    }

    @Test
    void theCacheDiscountsTheDeterministicCallsButNeverTheAnswers() {
        Breakdown b = CostEstimator.price(USAGE, new Price(2.0, 8.0), 0, 0.5);
        assertThat(b.generation().usd()).as("answers are not cached").isCloseTo(8.0, within(1e-12));
        assertThat(b.judging().usd()).isCloseTo(1.2, within(1e-12));
        assertThat(b.judging().calls()).isEqualTo(200);
        assertThat(b.rewriting().usd()).isCloseTo(0.4, within(1e-12));
        assertThat(b.cacheHitRate()).isEqualTo(0.5);
        assertThat(CostEstimator.price(USAGE, new Price(2.0, 8.0), 0, 5).cacheHitRate()).as("clamped to 1").isEqualTo(1.0);
        assertThat(CostEstimator.price(USAGE, new Price(2.0, 8.0), 0, 1).judging().calls()).isZero();
    }

    @Test
    void aLocalModelCostsNothingButStillCountsTokensAndCalls() {
        Breakdown b = CostEstimator.price(USAGE, Price.FREE, 0, 0);
        assertThat(b.usd()).isZero();
        assertThat(b.tokens()).isPositive();
        assertThat(b.calls()).isEqualTo(600);
        assertThat(CostEstimator.price(Usage.none(), new Price(2, 8), 0, 0).usd()).isZero();
    }

    @Test
    void aRunAbortsOnlyPastOneHundredTwentyPercentOfItsCap() {
        assertThat(CostEstimator.overCap(10.0, 10.0)).isFalse();
        assertThat(CostEstimator.overCap(12.0, 10.0)).as("exactly 120% is still allowed").isFalse();
        assertThat(CostEstimator.overCap(12.01, 10.0)).isTrue();
        assertThat(CostEstimator.overCap(1_000.0, (Double) null)).as("no cap, no abort").isFalse();
        assertThat(CostEstimator.overCap(6L, 5)).isFalse();
        assertThat(CostEstimator.overCap(7L, 5)).isTrue();
        assertThat(CostEstimator.overCap(7L, (Integer) null)).isFalse();
    }
}
