package dev.glassbox.ratelimit;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token bucket per key: holds up to {@code perMinute} tokens and refills continuously. In-memory and per process,
 * which is enough for one VM; a multi-node deployment would move this to the database.
 */
public class RateLimiter {
    private static final int PURGE_ABOVE = 10_000;

    private static final class Bucket {
        double tokens;
        long lastNanos;
    }

    private final double capacity;
    private final double refillPerNano;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(int perMinute) {
        this(perMinute, System::nanoTime);
    }

    public RateLimiter(int perMinute, LongSupplier nanoClock) {
        if (perMinute < 1) throw new IllegalArgumentException("perMinute must be positive");
        this.capacity = perMinute;
        this.refillPerNano = perMinute / 60_000_000_000.0;
        this.clock = nanoClock;
    }

    /** @return true if a token was available (and was taken). */
    public boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        if (buckets.size() > PURGE_ABOVE) purge(now);
        Bucket b = buckets.computeIfAbsent(key, k -> {
            Bucket nb = new Bucket();
            nb.tokens = capacity;
            nb.lastNanos = now;
            return nb;
        });
        synchronized (b) {
            b.tokens = Math.min(capacity, b.tokens + (now - b.lastNanos) * refillPerNano);
            b.lastNanos = now;
            if (b.tokens < 1) return false;
            b.tokens -= 1;
            return true;
        }
    }

    /** Seconds until one token is available again (at least 1). */
    public int secondsUntilToken() {
        return (int) Math.max(1, Math.ceil(60.0 / capacity));
    }

    private void purge(long now) {
        buckets.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().tokens + (now - e.getValue().lastNanos) * refillPerNano >= capacity;
            }
        });
    }
}
