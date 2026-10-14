package dev.glassbox.trace;

import static dev.glassbox.workspace.WorkspaceService.require;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.retrieval.Span;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import dev.glassbox.trace.TraceModel.Page;
import dev.glassbox.trace.TraceModel.Status;
import dev.glassbox.trace.TraceModel.Summary;
import dev.glassbox.trace.TraceModel.Trace;
import dev.glassbox.trace.TraceModel.TraceData;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class TraceService {
    private static final String SUMMARY_COLS = "id, kind, status, question, model, latency_ms, created_at";
    /** Old traces are deleted opportunistically, once per this many saves per process (see save). */
    private static final int PURGE_EVERY = 50;

    private final Tx tx;
    private final ObjectMapper json;
    private final int retentionDays;
    private final AtomicInteger saves = new AtomicInteger();

    public TraceService(Tx tx, ObjectMapper json, @Value("${glassbox.traces.retention-days}") int retentionDays) {
        this.tx = tx;
        this.json = json;
        this.retentionDays = retentionDays;
    }

    private static final RowMapper<Summary> SUMMARY = (rs, n) -> new Summary(rs.getObject("id", UUID.class), rs.getString("kind"),
            Status.valueOf(rs.getString("status")), rs.getString("question"), rs.getString("model"),
            (Integer) rs.getObject("latency_ms"), rs.getObject("created_at", OffsetDateTime.class));

    /**
     * Writes a trace in the workspace of the caller. Retention is enforced here, per workspace, because a global purge
     * would have to read every tenant's rows and row-level security forbids that by design.
     */
    public UUID save(UUID workspaceId, UUID userId, String kind, Status status, String question, String answer, String model,
                     Integer latencyMs, List<Span> spans, TraceData data) {
        try {
            String spansJson = json.writeValueAsString(spans);
            String dataJson = json.writeValueAsString(data);
            return tx.workspace(workspaceId, userId, j -> {
                UUID id = j.queryForObject("INSERT INTO traces (workspace_id, kind, status, question, answer, model, latency_ms, spans, data, created_by) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) RETURNING id", UUID.class,
                        workspaceId, kind, status.name(), question, answer, model, latencyMs, spansJson, dataJson, userId);
                if (saves.incrementAndGet() % PURGE_EVERY == 0) {
                    j.update("DELETE FROM traces WHERE created_at < now() - (? * interval '1 day')", retentionDays);
                }
                return id;
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not serialise trace", e);
        }
    }

    public Trace get(Principal p, UUID ws, UUID id) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            var rows = j.query("SELECT id, kind, status, question, answer, model, latency_ms, spans::text AS spans, data::text AS data, created_at "
                    + "FROM traces WHERE id = ?", (rs, n) -> {
                try {
                    return new Trace(rs.getObject("id", UUID.class), rs.getString("kind"), Status.valueOf(rs.getString("status")),
                            rs.getString("question"), rs.getString("answer"), rs.getString("model"), (Integer) rs.getObject("latency_ms"),
                            json.readValue(rs.getString("spans"), new TypeReference<List<Span>>() {}),
                            json.readValue(rs.getString("data"), TraceData.class), rs.getObject("created_at", OffsetDateTime.class));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("stored trace is unreadable: " + id, e);
                }
            }, id);
            if (rows.isEmpty()) throw ApiException.notFound("No such trace");
            return rows.get(0);
        });
    }

    public Page list(Principal p, UUID ws, int limit, String cursor) {
        int size = Math.max(1, Math.min(limit, 100));
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            List<Summary> rows;
            if (cursor == null || cursor.isBlank()) {
                rows = j.query("SELECT " + SUMMARY_COLS + " FROM traces ORDER BY created_at DESC, id DESC LIMIT ?", SUMMARY, size + 1);
            } else {
                Instant at;
                UUID id;
                try {
                    String[] parts = cursor.split("\\|", 2);
                    at = Instant.parse(parts[0]);
                    id = UUID.fromString(parts[1]);
                } catch (RuntimeException e) {
                    throw ApiException.badRequest("Invalid cursor");
                }
                rows = j.query("SELECT " + SUMMARY_COLS + " FROM traces WHERE (created_at, id) < (?::timestamptz, ?) "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?", SUMMARY, at.toString(), id, size + 1);
            }
            if (rows.size() <= size) return new Page(rows, null);
            List<Summary> items = new ArrayList<>(rows.subList(0, size));
            Summary last = items.get(size - 1);
            return new Page(items, last.createdAt().toInstant() + "|" + last.id());
        });
    }
}
