package dev.glassbox.ingest;

import static dev.glassbox.workspace.WorkspaceService.audit;
import static dev.glassbox.workspace.WorkspaceService.require;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Hashing;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.jobs.JobQueue;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import dev.glassbox.parse.FileTypes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {
    /** Part of the ingest idempotency key: bump to force re-ingestion after a pipeline change. */
    public static final String PIPELINE_VERSION = "p1";

    public record DocumentDto(UUID id, String title, String sourceType, String status, String error, int version,
                              long sizeBytes, OffsetDateTime createdAt) {}
    public record UploadResult(DocumentDto document, boolean duplicate) {}
    public record DocumentPage(List<DocumentDto> items, String nextCursor) {}
    public record SectionDto(int ordinal, Integer parentOrdinal, int level, List<String> headingPath, int start, int end) {}
    public record DocumentContent(DocumentDto document, String text, int[] pageStarts, List<SectionDto> sections) {}


    private final Tx tx;
    private final JobQueue jobs;
    private final DocumentStorage storage;
    private final ObjectMapper json;
    private final int maxDocuments;
    private final long maxBytes;

    public DocumentService(Tx tx, JobQueue jobs, DocumentStorage storage, ObjectMapper json,
                           @Value("${glassbox.limits.max-documents}") int maxDocuments,
                           @Value("${glassbox.limits.max-file-bytes}") long maxBytes) {
        this.tx = tx;
        this.jobs = jobs;
        this.storage = storage;
        this.json = json;
        this.maxDocuments = maxDocuments;
        this.maxBytes = maxBytes;
    }

    private static final RowMapper<DocumentDto> DTO = (rs, n) -> new DocumentDto(
            rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("source_type"), rs.getString("status"),
            rs.getString("error"), rs.getInt("version"), rs.getLong("size_bytes"),
            rs.getObject("created_at", OffsetDateTime.class));

    private static final String DTO_COLS = "id, title, source_type, status, error, version, size_bytes, created_at";

    // ------------------------------------------------------------ upload and replace

    public UploadResult upload(Principal p, UUID ws, String filename, byte[] data) {
        String type = sniff(filename, data);
        String hash = Hashing.sha256Hex(data);
        UUID docId = UUID.randomUUID();
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            var existing = j.query("SELECT " + DTO_COLS + " FROM documents WHERE content_hash = ?", DTO, hash);
            if (!existing.isEmpty()) return new UploadResult(existing.get(0), true);
            Integer count = j.queryForObject("SELECT count(*) FROM documents", Integer.class);
            if (count != null && count >= maxDocuments) {
                throw ApiException.unprocessable("Workspace limit of " + maxDocuments + " documents reached");
            }
            String path = storage.save(ws, docId, 1, filename, data);
            try {
                j.update("INSERT INTO documents (id, workspace_id, title, source_type, content_hash, size_bytes) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", docId, ws, title(filename), type, hash, (long) data.length);
                insertVersionAndJob(j, ws, docId, 1, hash, path);
                audit(j, ws, p.actor(), "document.upload", "document:" + docId);
                return new UploadResult(j.queryForObject("SELECT " + DTO_COLS + " FROM documents WHERE id = ?", DTO, docId), false);
            } catch (RuntimeException e) {
                storage.deleteDocument(ws, docId);
                throw e;
            }
        });
    }

    /** Uploads a new version of an existing document and re-ingests it. Old chunks are kept but marked superseded. */
    public DocumentDto replace(Principal p, UUID ws, UUID docId, String filename, byte[] data) {
        String type = sniff(filename, data);
        String hash = Hashing.sha256Hex(data);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            var rows = j.queryForList("SELECT version, content_hash, source_type FROM documents WHERE id = ? FOR UPDATE", docId);
            if (rows.isEmpty()) throw ApiException.notFound("No such document");
            if (hash.equals(rows.get(0).get("content_hash"))) throw ApiException.conflict("This file is identical to the current version");
            if (!j.query("SELECT 1 FROM documents WHERE content_hash = ? AND id <> ?", (rs, n) -> 1, hash, docId).isEmpty()) {
                throw ApiException.conflict("An identical file already exists in this workspace");
            }
            int version = (Integer) rows.get(0).get("version") + 1;
            String path = storage.save(ws, docId, version, filename, data);
            // Old chunks stay (history, change impact) but are no longer searchable: drop their vectors, flag them.
            j.update("DELETE FROM embeddings WHERE chunk_id IN (SELECT id FROM chunks WHERE document_id = ?)", docId);
            j.update("UPDATE chunks SET superseded = true WHERE document_id = ?", docId);
            j.update("UPDATE documents SET version = ?, content_hash = ?, source_type = ?, title = ?, size_bytes = ?, "
                    + "status = 'UPLOADED', error = NULL WHERE id = ?", version, hash, type, title(filename), (long) data.length, docId);
            insertVersionAndJob(j, ws, docId, version, hash, path);
            audit(j, ws, p.actor(), "document.replace", "document:" + docId + "@v" + version);
            return j.queryForObject("SELECT " + DTO_COLS + " FROM documents WHERE id = ?", DTO, docId);
        });
    }

    private void insertVersionAndJob(JdbcTemplate j, UUID ws, UUID docId, int version, String hash, String path) {
        j.update("INSERT INTO document_versions (workspace_id, document_id, version, content_hash, storage_path) "
                + "VALUES (?, ?, ?, ?, ?)", ws, docId, version, hash, path);
        // Idempotency key: workspace, content hash and pipeline version (SPEC 5).
        jobs.enqueue(j, ws, IngestHandler.TYPE, json.valueToTree(Map.of("documentId", docId.toString(), "version", version)),
                ws + ":" + hash + ":" + PIPELINE_VERSION);
        DocumentEvents.statusChanged(j, ws, docId, "UPLOADED");
    }

    // ------------------------------------------------------------ read

    public DocumentPage list(Principal p, UUID ws, int limit, String cursor) {
        int size = Math.max(1, Math.min(limit, 200));
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            List<DocumentDto> rows;
            if (cursor == null || cursor.isBlank()) {
                rows = j.query("SELECT " + DTO_COLS + " FROM documents ORDER BY created_at DESC, id DESC LIMIT ?", DTO, size + 1);
            } else {
                String[] parts = cursor.split("\\|", 2);
                Instant at;
                UUID id;
                try {
                    at = Instant.parse(parts[0]);
                    id = UUID.fromString(parts[1]);
                } catch (RuntimeException e) {
                    throw ApiException.badRequest("Invalid cursor");
                }
                rows = j.query("SELECT " + DTO_COLS + " FROM documents WHERE (created_at, id) < (?::timestamptz, ?) "
                        + "ORDER BY created_at DESC, id DESC LIMIT ?", DTO, at.toString(), id, size + 1);
            }
            if (rows.size() <= size) return new DocumentPage(rows, null);
            List<DocumentDto> items = new ArrayList<>(rows.subList(0, size));
            DocumentDto last = items.get(size - 1);
            return new DocumentPage(items, last.createdAt().toInstant() + "|" + last.id());
        });
    }

    public DocumentContent content(Principal p, UUID ws, UUID docId) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            var docs = j.query("SELECT " + DTO_COLS + " FROM documents WHERE id = ?", DTO, docId);
            if (docs.isEmpty()) throw ApiException.notFound("No such document");
            DocumentDto doc = docs.get(0);
            var rows = j.queryForList("SELECT id, normalized_text, page_starts FROM document_versions "
                    + "WHERE document_id = ? AND version = ?", docId, doc.version());
            if (rows.isEmpty() || rows.get(0).get("normalized_text") == null) {
                return new DocumentContent(doc, null, new int[0], List.of());
            }
            List<SectionDto> sections = j.query(
                    "SELECT ordinal, parent_ordinal, level, heading_path, char_start, char_end FROM ("
                            + "SELECT s.ordinal, p.ordinal AS parent_ordinal, s.level, s.heading_path, s.char_start, s.char_end "
                            + "FROM sections s LEFT JOIN sections p ON p.id = s.parent_id "
                            + "WHERE s.document_version_id = ?) q ORDER BY ordinal",
                    (rs, n) -> new SectionDto(rs.getInt("ordinal"), (Integer) rs.getObject("parent_ordinal"), rs.getInt("level"),
                            strings(rs.getArray("heading_path")), rs.getInt("char_start"), rs.getInt("char_end")),
                    rows.get(0).get("id"));
            Array pages = (Array) rows.get(0).get("page_starts");
            return new DocumentContent(doc, (String) rows.get(0).get("normalized_text"), ints(pages), sections);
        });
    }

    public void delete(Principal p, UUID ws, UUID docId) {
        tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            if (j.update("DELETE FROM documents WHERE id = ?", docId) == 0) throw ApiException.notFound("No such document");
            audit(j, ws, p.actor(), "document.delete", "document:" + docId);
            return null;
        });
        storage.deleteDocument(ws, docId);
    }

    // ------------------------------------------------------------ helpers

    /** Detects the real type from the bytes (the name only breaks ties) and enforces the size limit. */
    String sniff(String filename, byte[] data) {
        if (data.length == 0) throw ApiException.badRequest("Empty file");
        if (data.length > maxBytes) throw ApiException.tooLarge("File exceeds the 25 MB limit");
        dev.glassbox.parse.ArchiveGuard.problem(data).ifPresent(why -> {
            throw ApiException.unprocessable("This file was refused: " + why);
        });
        try {
            return FileTypes.classify(filename, data).orElseThrow(() -> ApiException.unsupportedMediaType(
                    "Glassbox could not recognise this file as text, a document, a spreadsheet, a presentation, an e-book or a data file."));
        } catch (FileTypes.NotSupported e) {
            throw ApiException.unsupportedMediaType(e.getMessage());
        }
    }

    static String title(String filename) {
        String name = filename == null ? "Untitled" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name).strip().isEmpty() ? "Untitled" : (dot > 0 ? name.substring(0, dot) : name).strip();
    }

    private static List<String> strings(Array a) {
        try {
            return a == null ? List.of() : List.of((String[]) a.getArray());
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int[] ints(Array a) {
        try {
            if (a == null) return new int[0];
            Integer[] boxed = (Integer[]) a.getArray();
            int[] out = new int[boxed.length];
            for (int i = 0; i < out.length; i++) out[i] = boxed[i];
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
