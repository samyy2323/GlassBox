package dev.glassbox.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.chunking.Chunker;
import dev.glassbox.chunking.Chunker.ChunkOut;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Parses a stored file and writes one chunking of one document version. Shared by the upload job and by re-indexing, which adds
 * another chunking (or embedding model) to a version that is already READY. Several chunkings of a version coexist, so the
 * version's sections are written once and then reused, never deleted.
 */
@Component
public class DocumentIndexer {
    private final Tx tx;
    private final ObjectMapper json;
    private final Duration parseTimeout;
    private final ExecutorService parsePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "parser");
        t.setDaemon(true);
        return t;
    });

    public DocumentIndexer(Tx tx, ObjectMapper json, @Value("${glassbox.ingest.parse-timeout}") Duration parseTimeout) {
        this.tx = tx;
        this.json = json;
        this.parseTimeout = parseTimeout;
    }

    /** Parses outside any transaction, with a timeout. */
    public ParsedDocument parse(String sourceType, byte[] data) throws Exception {
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

    /** Idempotent: a retry deletes and rewrites this version's chunks of this chunking only. Returns the chunking id. */
    public UUID persist(UUID ws, UUID docId, UUID versionId, ParsedDocument parsed, Chunker chunker) throws Exception {
        List<ChunkOut> chunks = chunker.chunk(parsed);
        String params = json.writeValueAsString(chunker.params());
        List<ParsedDocument.Section> sections = parsed.sections();
        return tx.workspace(ws, null, j -> {
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

            // Retry-safe: remove anything a previous attempt wrote for this version and chunking.
            j.update("DELETE FROM chunks WHERE document_version_id = ? AND chunking_id = ?", versionId, chunkingId);

            List<UUID> sectionIds = new ArrayList<>();
            List<UUID> existing = j.queryForList("SELECT id FROM sections WHERE document_version_id = ? ORDER BY ordinal", UUID.class, versionId);
            if (existing.size() == sections.size()) {
                sectionIds.addAll(existing);
            } else {
                j.update("DELETE FROM sections WHERE document_version_id = ?", versionId);
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
            }
            // A parent chunk has a lower ordinal than its children, so inserting in order satisfies the parent FK.
            List<UUID> chunkIds = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) chunkIds.add(UUID.randomUUID());
            j.batchUpdate("INSERT INTO chunks (id, workspace_id, document_id, document_version_id, section_id, chunking_id, ordinal, "
                    + "text, token_count, char_start, char_end, page, role, parent_chunk_id) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    chunks, 200, (ps, c) -> {
                        ps.setObject(1, chunkIds.get(c.ordinal()));
                        ps.setObject(2, ws);
                        ps.setObject(3, docId);
                        ps.setObject(4, versionId);
                        ps.setObject(5, sectionIds.get(c.sectionOrdinal()));
                        ps.setObject(6, chunkingId);
                        ps.setInt(7, c.ordinal());
                        ps.setString(8, c.text());
                        ps.setInt(9, c.tokenCount());
                        ps.setInt(10, c.start());
                        ps.setInt(11, c.end());
                        ps.setObject(12, c.page());
                        ps.setString(13, c.role().name());
                        ps.setObject(14, c.parentOrdinal() == null ? null : chunkIds.get(c.parentOrdinal()));
                    });
            return chunkingId;
        });
    }
}
