package dev.glassbox.ingest;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Publishes document status changes over Postgres NOTIFY; delivered to listeners when the transaction commits. */
public final class DocumentEvents {
    public static final String CHANNEL = "doc_status";

    private DocumentEvents() {}

    public static void statusChanged(JdbcTemplate j, UUID workspaceId, UUID documentId, String status) {
        String payload = "{\"workspaceId\":\"" + workspaceId + "\",\"documentId\":\"" + documentId
                + "\",\"status\":\"" + status + "\"}";
        j.queryForObject("SELECT pg_notify(?, ?)", String.class, CHANNEL, payload);
    }
}
