package dev.glassbox.retrieval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.chunking.Chunker;
import dev.glassbox.ingest.IngestDefaults;
import dev.glassbox.tenancy.Tx;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Finds the chunking row a pipeline searches: the one that uploads create by default, or the one a config names. */
@Component
public class ChunkingResolver {
    private final Tx tx;
    private final ObjectMapper json;
    private final int chunkSize;

    public ChunkingResolver(Tx tx, ObjectMapper json, @Value("${glassbox.ingest.chunk-size}") int chunkSize) {
        this.tx = tx;
        this.json = json;
        this.chunkSize = chunkSize;
    }

    public Chunker defaultChunker() {
        return IngestDefaults.chunker(chunkSize);
    }

    public Optional<UUID> defaultChunkingId(UUID workspaceId) {
        return idOf(workspaceId, defaultChunker());
    }

    /** {@code chunking == null} means the default. Empty when nothing was ever ingested with that chunking. */
    public Optional<UUID> resolve(UUID workspaceId, Chunking chunking) {
        return chunking == null ? defaultChunkingId(workspaceId) : idOf(workspaceId, chunking.chunker());
    }

    private Optional<UUID> idOf(UUID workspaceId, Chunker chunker) {
        try {
            String params = json.writeValueAsString(chunker.params());
            List<UUID> ids = tx.workspace(workspaceId, null, j -> j.queryForList(
                    "SELECT id FROM chunkings WHERE strategy = ? AND params = ?::jsonb", UUID.class, chunker.strategy(), params));
            return ids.stream().findFirst();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
