package dev.glassbox.observability;

import dev.glassbox.tenancy.Tx;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * {@code ingest_queue_depth} (jobs waiting for a worker) and {@code experiment_runs_active} (experiments and AutoTune searches queued or running), read from the database
 * when Prometheus scrapes and cached for a few seconds so a scrape never costs more than one cheap query. Both are counted across all workspaces: they describe the
 * system, not a tenant.
 */
@Component
public class QueueMetrics {
    private static final long CACHE_MILLIS = 5_000;

    public QueueMetrics(Tx tx, MeterRegistry registry) {
        Gauge.builder("ingest_queue_depth", cached(() -> tx.worker(j -> j.queryForObject("SELECT count(*) FROM jobs WHERE status = 'QUEUED'", Long.class)))).register(registry);
        Gauge.builder("experiment_runs_active", cached(() -> tx.worker(j -> j.queryForObject(
                "SELECT (SELECT count(*) FROM experiments WHERE status IN ('QUEUED', 'RUNNING')) + (SELECT count(*) FROM autotunes WHERE status IN ('QUEUED', 'RUNNING'))",
                Long.class)))).register(registry);
    }

    private static Supplier<Number> cached(Supplier<Long> query) {
        long[] at = {0};
        double[] value = {0};
        return () -> {
            synchronized (at) {
                long now = System.currentTimeMillis();
                if (now - at[0] > CACHE_MILLIS) {
                    try {
                        Long v = query.get();
                        value[0] = v == null ? 0 : v;
                    } catch (RuntimeException e) {
                        value[0] = Double.NaN; // the database is unreachable: say so instead of reporting a stale zero
                    }
                    at[0] = now;
                }
                return value[0];
            }
        };
    }
}
