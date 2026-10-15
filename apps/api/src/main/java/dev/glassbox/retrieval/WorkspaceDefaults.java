package dev.glassbox.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.tenancy.Tx;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** The pipeline a workspace promoted from an AutoTune result. Chat and search start from it instead of the built-in default. */
@Component
public class WorkspaceDefaults {
    private final Tx tx;
    private final ObjectMapper json;

    public WorkspaceDefaults(Tx tx, ObjectMapper json) {
        this.tx = tx;
        this.json = json;
    }

    public Optional<PipelineConfig> promoted(UUID workspaceId) {
        return tx.workspace(workspaceId, null, j -> j.query("SELECT default_pipeline::text FROM workspace_settings WHERE workspace_id = ? AND default_pipeline IS NOT NULL",
                (rs, n) -> rs.getString(1), workspaceId).stream().findFirst()).map(text -> {
            try {
                return json.readValue(text, PipelineConfig.class);
            } catch (Exception e) {
                throw new IllegalStateException("the promoted pipeline of workspace " + workspaceId + " is unreadable", e);
            }
        });
    }

    public void promote(UUID workspaceId, UUID userId, PipelineConfig config) {
        String text;
        try {
            text = json.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        tx.workspace(workspaceId, userId, j -> j.update("INSERT INTO workspace_settings (workspace_id, default_pipeline, updated_by, updated_at) VALUES (?, ?::jsonb, ?, now()) "
                + "ON CONFLICT (workspace_id) DO UPDATE SET default_pipeline = EXCLUDED.default_pipeline, updated_by = EXCLUDED.updated_by, updated_at = now()", workspaceId, text, userId));
    }

    public void clear(UUID workspaceId, UUID userId) {
        tx.workspace(workspaceId, userId, j -> j.update("UPDATE workspace_settings SET default_pipeline = NULL, updated_at = now() WHERE workspace_id = ?", workspaceId));
    }
}
