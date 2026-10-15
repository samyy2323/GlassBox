package dev.glassbox.ingest;

import dev.glassbox.chunking.Chunker;
import dev.glassbox.embed.EmbeddingProviders;
import dev.glassbox.embed.EmbeddingService;
import dev.glassbox.jobs.Job;
import dev.glassbox.jobs.JobHandler;
import dev.glassbox.parse.ParsedDocument;
import dev.glassbox.parse.UnsupportedDocumentException;
import dev.glassbox.tenancy.Tx;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ingestion job: parse the stored file, save sections and chunks, then mark the document READY.
 * Idempotent: a retry deletes and rewrites this version's sections and chunks.
 */
@Component
public class IngestHandler implements JobHandler {
    public static final String TYPE = "INGEST";
    private static final Logger log = LoggerFactory.getLogger(IngestHandler.class);

    private final Tx tx;
    private final EmbeddingService embeddings;
    private final EmbeddingProviders providers;
    private final DocumentStorage storage;
    private final DocumentIndexer indexer;
    private final int chunkSize;

    public IngestHandler(Tx tx, EmbeddingService embeddings, EmbeddingProviders providers, DocumentStorage storage, DocumentIndexer indexer,
                         @Value("${glassbox.ingest.chunk-size}") int chunkSize) {
        this.tx = tx;
        this.embeddings = embeddings;
        this.providers = providers;
        this.storage = storage;
        this.indexer = indexer;
        this.chunkSize = chunkSize;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        UUID ws = job.workspaceId();
        UUID docId = UUID.fromString(job.payload().get("documentId").asText());
        int version = job.payload().get("version").asInt();

        // 1. Claim the version. A deleted or superseded version is skipped silently.
        record Target(UUID versionId, String sourceType, String path) {}
        Target target = tx.workspace(ws, null, j -> {
            var rows = j.queryForList("SELECT v.id, d.source_type, v.storage_path FROM documents d "
                    + "JOIN document_versions v ON v.document_id = d.id AND v.version = d.version "
                    + "WHERE d.id = ? AND d.version = ?", docId, version);
            if (rows.isEmpty()) return null;
            j.update("UPDATE documents SET status = 'PROCESSING', error = NULL WHERE id = ?", docId);
            DocumentEvents.statusChanged(j, ws, docId, "PROCESSING");
            var r = rows.get(0);
            return new Target((UUID) r.get("id"), (String) r.get("source_type"), (String) r.get("storage_path"));
        });
        if (target == null) {
            log.info("ingest {}: document {} v{} no longer current, skipping", job.id(), docId, version);
            return;
        }

        // 2. Parse (outside any transaction, with a timeout).
        ParsedDocument parsed;
        try {
            parsed = indexer.parse(target.sourceType(), storage.read(target.path()));
        } catch (UnsupportedDocumentException e) {
            fail(ws, docId, "FAILED_UNSUPPORTED", e.getMessage());
            return;
        }

        // 3. Chunk and persist.
        Chunker chunker = IngestDefaults.chunker(chunkSize);
        UUID chunkingId = indexer.persist(ws, docId, target.versionId(), parsed, chunker);

        // 4. Embed (idempotent; reuses vectors of identical text). A provider outage fails the job, which retries.
        var embedded = embeddings.embedChunks(ws, target.versionId(), chunkingId, providers.defaultModel());
        log.info("ingest {}: {} chunks, {} reused, {} texts embedded", job.id(), embedded.chunks(), embedded.reused(), embedded.textsEmbedded());

        // 5. Done.
        tx.workspace(ws, null, j -> {
            j.update("UPDATE documents SET status = 'READY' WHERE id = ? AND version = ?", docId, version);
            DocumentEvents.statusChanged(j, ws, docId, "READY");
            return null;
        });
    }

    @Override
    public void onDead(Job job, String error) {
        UUID docId = UUID.fromString(job.payload().get("documentId").asText());
        fail(job.workspaceId(), docId, "FAILED", "Ingestion failed after " + JobQueueLimit.ATTEMPTS + " attempts");
    }

    private void fail(UUID ws, UUID docId, String status, String message) {
        tx.workspace(ws, null, j -> {
            j.update("UPDATE documents SET status = ?, error = ? WHERE id = ?", status, message, docId);
            DocumentEvents.statusChanged(j, ws, docId, status);
            return null;
        });
    }

    /** Kept next to the handler so the message cannot drift from the queue's limit. */
    private static final class JobQueueLimit {
        static final int ATTEMPTS = dev.glassbox.jobs.JobQueue.MAX_ATTEMPTS;
    }
}
