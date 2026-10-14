package dev.glassbox.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.glassbox.common.ApiException;
import dev.glassbox.generate.LlmGate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RateLimiterAndGateTest {
    static final long SECOND = 1_000_000_000L;

    @Test
    void allowsABurstUpToTheLimitThenRefusesUntilTokensRefill() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(6, now::get); // 6 per minute = one token every 10 s
        for (int i = 0; i < 6; i++) assertThat(limiter.tryAcquire("a")).as("burst %d", i).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();

        now.addAndGet(9 * SECOND);
        assertThat(limiter.tryAcquire("a")).as("not yet refilled").isFalse();
        now.addAndGet(1 * SECOND);
        assertThat(limiter.tryAcquire("a")).as("one token after 10 s").isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void keysAreIndependentAndTokensNeverExceedCapacity() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(2, now::get);
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).as("another key has its own bucket").isTrue();

        now.addAndGet(3600 * SECOND); // an hour idle must not bank more than the capacity
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
    }

    @Test
    void retryAfterMatchesTheRefillRate() {
        assertThat(new RateLimiter(20).secondsUntilToken()).isEqualTo(3);
        assertThat(new RateLimiter(1).secondsUntilToken()).isEqualTo(60);
        assertThat(new RateLimiter(600).secondsUntilToken()).isEqualTo(1);
    }

    @Test
    void gateAdmitsUpToItsLimitThenAnswers429WithRetryAfter() {
        LlmGate gate = new LlmGate(2, Duration.ofMillis(50), new SimpleMeterRegistry());
        LlmGate.Permit p1 = gate.acquire();
        LlmGate.Permit p2 = gate.acquire();

        assertThatThrownBy(gate::acquire).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(e.retryAfterSeconds()).isPositive();
        });

        p1.close();
        LlmGate.Permit p3 = gate.acquire(); // the freed slot is usable again
        p1.close();                          // closing twice must not free a second slot
        assertThatThrownBy(gate::acquire).isInstanceOf(ApiException.class);
        p2.close();
        p3.close();
        gate.acquire().close();
    }

    @Test
    void aWaitingRequestIsServedWhenASlotFreesWithinTheWindow() throws Exception {
        LlmGate gate = new LlmGate(1, Duration.ofSeconds(2), new SimpleMeterRegistry());
        LlmGate.Permit first = gate.acquire();
        Thread releaser = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            first.close();
        });
        releaser.start();
        long t = System.nanoTime();
        gate.acquire().close();
        assertThat((System.nanoTime() - t) / 1_000_000).isBetween(50L, 1500L);
        releaser.join();
    }
}
