package dev.glassbox.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.chunking.Chunker;
import dev.glassbox.chunking.Chunker.ChunkOut;
import dev.glassbox.chunking.RecursiveChunker;
import dev.glassbox.jobs.Job;
import dev.glassbox.jobs.JobHandler;
import dev.glassbox.parse.DocumentParser;
import dev.glassbox.parse.ParsedDocument;
import dev.glassbox.parse.UnsupportedDocumentException;
import dev.glassbox.tenancy.Tx;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final DocumentStorage storage;
    private final ObjectMapper json;
    private final int chunkSize;
    private final Duration parseTimeout;
    private final ExecutorService parsePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "parser");
        t.setDaemon(true);
        return t;
    });

    public IngestHandler(Tx tx, DocumentStorage storage, ObjectMapper json,
                         @Value("${glassbox.ingest.chunk-size}") int chunkSize,
                         @Value("${glassbox.ingest.parse-timeout}") Duration parseTimeout) {
        this.tx = tx;
        this.storage = storage;
        this.json = json;
        this.chunkSize = chunkSize;
        this.parseTimeout = parseTimeout;
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
            parsed = parse(target.sourceType(), storage.read(target.path()));
        } catch (UnsupportedDocumentException e) {
            fail(ws, docId, "FAILED_UNSUPPORTED", e.getMessage());
            return;
        }

        // 3. Chunk and persist.
        Chunker chunker = new RecursiveChunker(chunkSize, true);
        List<ChunkOut> chunks = chunker.chunk(parsed);
        persist(ws, docId, target.versionId(), parsed, chunker, chunks);

        // 4. Done. (Embedding is added between persist and READY.)
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

    private ParsedDocument parse(String sourceType, byte[] data) throws Exception {
        Future<ParsedDocument> f = parsePool.submit(() -> DocumentParser.forType(sourceType).parse(data));
        try {
            return f.get(parseTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            f.cancel(true);
            throw new UnsupportedDocumentException("Parsing took longer than " + parseTimeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof UnsupportedDocumentException u) throw u;
            if (c instanceof IOException io) throw new UnsupportedDocumentException("The file could not be read: " + io.getMessage());
            if (c instanceof RuntimeException re) throw new UnsupportedDocumentException("The file could not be read: " + re.getMessage());
            throw e;
        }
    }

    private void persist(UUID ws, UUID docId, UUID versionId, ParsedDocument parsed, Chunker chunker, List<ChunkOut> chunks) throws Exception {
        String params = json.writeValueAsString(chunker.params());
        List<ParsedDocument.Section> sections = parsed.sections();
        tx.workspace(ws, null, j -> {
            Integer[] pages = java.util.Arrays.stream(parsed.pageStarts()).boxed().toArray(Integer[]::new);
            j.update(con -> {
                var ps = con.prepareStatement("UPDATE document_versions SET normalized_text = ?, page_starts = ? WHERE id = ?");
                ps.setString(1, parsed.text());
                ps.setArray(2, con.createArrayOf("integer", pages));
                ps.setObject(3, versionId);
                return ps;
            });
            UUID chunkingId = j.queryForObject("INSERT INTO chunkings (workspace_id, strategy, params) VALUES (?, ?, ?::jsonb) "
                    + "ON CONFLICT (workspace_id, strategy, params) DO UPDATE SET strategy = EXCLUDED.strategy RETURNING id",
                    UUID.class, ws, chunker.strategy(), params);

            // Retry-safe: remove anything a previous attempt wrote for this version.
            j.update("DELETE FROM chunks WHERE document_version_id = ? AND chunking_id = ?", versionId, chunkingId);
            j.update("DELETE FROM sections WHERE document_version_id = ?", versionId);

            List<UUID> sectionIds = new ArrayList<>();
            for (int i = 0; i < sections.size(); i++) sectionIds.add(UUID.randomUUID());
            // Parents have lower ordinals than their children, so inserting in order satisfies the FK.
            j.batchUpdate("INSERT INTO sections (id, workspace_id, document_id, document_version_id, parent_id, heading_path, "
                    + "ordinal, level, char_start, char_end, text) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    sections, 100, (ps, s) -> {
                        ps.setObject(1, sectionIds.get(s.ordinal()));
                        ps.setObject(2, ws);
                        ps.setObject(3, docId);
                        ps.setObject(4, versionId);
                        ps.setObject(5, s.parentOrdinal() == null ? null : sectionIds.get(s.parentOrdinal()));
                        ps.setArray(6, ps.getConnection().createArrayOf("text", s.headingPath().toArray()));
                        ps.setInt(7, s.ordinal());
                        ps.setInt(8, s.level());
                        ps.setInt(9, s.start());
                        ps.setInt(10, s.end());
                        ps.setString(11, parsed.text().substring(s.start(), s.end()));
                    });
            j.batchUpdate("INSERT INTO chunks (workspace_id, document_id, document_version_id, section_id, chunking_id, ordinal, "
                    + "text, token_count, char_start, char_end, page) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    chunks, 200, (ps, c) -> {
                        ps.setObject(1, ws);
                        ps.setObject(2, docId);
                        ps.setObject(3, versionId);
                        ps.setObject(4, sectionIds.get(c.sectionOrdinal()));
                        ps.setObject(5, chunkingId);
                        ps.setInt(6, c.ordinal());
                        ps.setString(7, c.text());
                        ps.setInt(8, c.tokenCount());
                        ps.setInt(9, c.start());
                        ps.setInt(10, c.end());
                        ps.setObject(11, c.page());
                    });
            return null;
        });
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
