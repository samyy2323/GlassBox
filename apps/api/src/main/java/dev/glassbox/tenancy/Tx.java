package dev.glassbox.tenancy;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in a transaction with the Postgres request context set, so Row-Level Security applies.
 * set_config(..., true) is the parameterisable form of SET LOCAL: it lasts until the transaction ends.
 * The runtime role cannot bypass RLS, so a missing context returns no rows (fail closed).
 */
@Component
public class Tx {
    private final TransactionTemplate tt;
    private final JdbcTemplate jdbc;
    private final Counter contextMissing;

    public Tx(TransactionTemplate tt, JdbcTemplate jdbc, MeterRegistry registry) {
        this.tt = tt;
        this.jdbc = jdbc;
        this.contextMissing = registry.counter("rls_context_missing_total");
    }

    /** Tenant-scoped work. {@code user} may be null (API-key and worker callers). */
    public <T> T workspace(UUID workspaceId, UUID userId, Function<JdbcTemplate, T> work) {
        if (workspaceId == null) {
            contextMissing.increment();
            throw new IllegalStateException("workspace context required");
        }
        return run(workspaceId, userId, work);
    }

    /** User-scoped work (own workspaces, refresh tokens). */
    public <T> T user(UUID userId, Function<JdbcTemplate, T> work) {
        if (userId == null) {
            contextMissing.increment();
            throw new IllegalStateException("user context required");
        }
        return run(null, userId, work);
    }

    /**
     * Queue-internal work that must see jobs of every workspace (claim, reaper). Never use for tenant data:
     * only the {@code jobs} policy honours this setting.
     */
    public <T> T worker(Function<JdbcTemplate, T> work) {
        return run(null, null, true, work);
    }

    /** Pre-authentication work (login lookup, registration) through SECURITY DEFINER functions only. */
    public <T> T anonymous(Function<JdbcTemplate, T> work) {
        return run(null, null, work);
    }

    private <T> T run(UUID ws, UUID user, Function<JdbcTemplate, T> work) {
        return run(ws, user, false, work);
    }

    private <T> T run(UUID ws, UUID user, boolean worker, Function<JdbcTemplate, T> work) {
        return tt.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.worker', ?, true)", String.class, worker ? "on" : "");
            jdbc.queryForObject("SELECT set_config('app.workspace_id', ?, true)", String.class,
                    ws == null ? "" : ws.toString());
            jdbc.queryForObject("SELECT set_config('app.user_id', ?, true)", String.class,
                    user == null ? "" : user.toString());
            return work.apply(jdbc);
        });
    }
}
