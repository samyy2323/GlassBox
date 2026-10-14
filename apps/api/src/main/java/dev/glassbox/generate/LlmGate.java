package dev.glassbox.generate;

import dev.glassbox.common.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits concurrent generations. A small free server handles one answer at a time, so a second request waits briefly
 * and is then refused with HTTP 429 + Retry-After instead of piling up and exhausting memory.
 */
@Component
public class LlmGate {
    /** Held for the duration of a generation; closing it frees the slot. Idempotent. */
    public final class Permit implements AutoCloseable {
        private boolean released;

        @Override
        public synchronized void close() {
            if (released) return;
            released = true;
            inFlight.decrementAndGet();
            semaphore.release();
        }
    }

    private final Semaphore semaphore;
    private final Duration queueWait;
    private final Counter rejected;
    private final AtomicInteger inFlight = new AtomicInteger();

    public LlmGate(@Value("${glassbox.llm.max-concurrent}") int maxConcurrent,
                   @Value("${glassbox.llm.queue-wait}") Duration queueWait, MeterRegistry registry) {
        this.semaphore = new Semaphore(maxConcurrent, true);
        this.queueWait = queueWait;
        this.rejected = registry.counter("llm_requests_rejected_total");
        registry.gauge("llm_generations_in_flight", inFlight);
    }

    public Permit acquire() {
        try {
            if (!semaphore.tryAcquire(queueWait.toMillis(), TimeUnit.MILLISECONDS)) {
                rejected.increment();
                throw ApiException.tooManyRequests("The assistant is busy answering another question. Please try again in a few seconds.", 5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.tooManyRequests("Interrupted while waiting for the assistant", 5);
        }
        inFlight.incrementAndGet();
        return new Permit();
    }
}
