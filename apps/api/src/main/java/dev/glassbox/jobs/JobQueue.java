package dev.glassbox.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.tenancy.Tx;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Postgres-backed job queue (SPEC 5). Workers claim one job at a time with FOR UPDATE SKIP LOCKED, so
 * concurrent workers never process the same job. Claiming and reaping run in worker mode (they must see
 * every workspace); the job itself then runs inside its own workspace context.
 */
@Component
public class JobQueue {
    public static final int MAX_ATTEMPTS = 5;

    // The claim statement from SPEC 5.
    private static final String CLAIM = """
            UPDATE jobs SET status = 'RUNNING', locked_by = ?, locked_at = now(), attempts = attempts + 1
            WHERE id = (SELECT id FROM jobs
                        WHERE status = 'QUEUED' AND run_after <= now()
                        ORDER BY run_after FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING id, workspace_id, type, payload, attempts
            """;

    private final Tx tx;
    private final ObjectMapper json;
    private final Counter retries;
    private final RowMapper<Job> mapper;

    public JobQueue(Tx tx, ObjectMapper json, MeterRegistry registry) {
        this.tx = tx;
        this.json = json;
        this.retries = registry.counter("job_retries_total");
        this.mapper = (rs, n) -> {
            try {
                return new Job(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                        rs.getString("type"), json.readTree(rs.getString("payload")), rs.getInt("attempts"));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** Enqueues inside an existing workspace transaction. A repeated idempotency key is a no-op. */
    public void enqueue(JdbcTemplate j, UUID workspaceId, String type, JsonNode payload, String idempotencyKey) {
        j.update("INSERT INTO jobs (workspace_id, type, payload, idempotency_key) VALUES (?, ?, ?::jsonb, ?) "
                + "ON CONFLICT (idempotency_key) DO NOTHING", workspaceId, type, payload.toString(), idempotencyKey);
    }

    public Optional<Job> claim(String workerId) {
        return tx.worker(j -> j.query(CLAIM, mapper, workerId).stream().findFirst());
    }

    public void complete(Job job) {
        tx.workspace(job.workspaceId(), null, j -> j.update(
                "UPDATE jobs SET status = 'DONE', locked_by = NULL, locked_at = NULL, error = NULL WHERE id = ?", job.id()));
    }

    /** Requeues with exponential backoff (10 s * 2^attempts), or marks DEAD after the last attempt. */
    public boolean fail(Job job, String error) {
        boolean dead = job.attempts() >= MAX_ATTEMPTS;
        String msg = error == null ? "unknown error" : error.substring(0, Math.min(2000, error.length()));
        tx.workspace(job.workspaceId(), null, j -> j.update(
                "UPDATE jobs SET status = ?, locked_by = NULL, locked_at = NULL, error = ?, "
                        + "run_after = now() + (interval '10 seconds' * power(2, attempts)) WHERE id = ?",
                dead ? "DEAD" : "QUEUED", msg, job.id()));
        if (!dead) retries.increment();
        return dead;
    }

    /** Requeues jobs whose worker vanished (lock older than 5 minutes). Returns how many were touched. */
    public int reapStale() {
        return tx.worker(j -> j.update(
                "UPDATE jobs SET status = CASE WHEN attempts >= ? THEN 'DEAD' ELSE 'QUEUED' END, "
                        + "locked_by = NULL, locked_at = NULL, error = 'worker lost the job (lock expired)' "
                        + "WHERE status = 'RUNNING' AND locked_at < now() - interval '5 minutes'", MAX_ATTEMPTS));
    }

    public long queueDepth() {
        Long n = tx.worker(j -> j.queryForObject("SELECT count(*) FROM jobs WHERE status = 'QUEUED'", Long.class));
        return n == null ? 0 : n;
    }
}
