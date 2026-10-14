package dev.glassbox.embed;

import dev.glassbox.auth.Hashing;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Embeds the chunks of one document version. Idempotent and cheap to re-run: chunks that already have a
 * vector for the model are skipped, and a text that was embedded before in the workspace (same content
 * hash) copies the stored vector instead of calling the provider again.
 */
@Service
public class EmbeddingService {
    public record Result(int chunks, int alreadyEmbedded, int reused, int textsEmbedded) {}

    private record Pending(UUID chunkId, String hash, String text) {}

    private final Tx tx;
    private final EmbeddingProviders providers;

    public EmbeddingService(Tx tx, EmbeddingProviders providers) {
        this.tx = tx;
        this.providers = providers;
    }

    public Result embedChunks(UUID ws, UUID versionId, UUID chunkingId, String model) {
        EmbeddingProvider provider = providers.get(model);

        int total = count(ws, "SELECT count(*) FROM chunks WHERE document_version_id = ? AND chunking_id = ?", versionId, chunkingId);
        List<Pending> pending = tx.workspace(ws, null, j -> j.query(
                "SELECT c.id, c.text FROM chunks c WHERE c.document_version_id = ? AND c.chunking_id = ? "
                        + "AND NOT EXISTS (SELECT 1 FROM embeddings e WHERE e.chunk_id = c.id AND e.model = ?) ORDER BY c.ordinal",
                (rs, n) -> new Pending(rs.getObject("id", UUID.class), Hashing.sha256Hex(rs.getString("text")), rs.getString("text")),
                versionId, chunkingId, model));
        if (pending.isEmpty()) return new Result(total, total, 0, 0);

        // 1. Reuse vectors of identical text already embedded in this workspace.
        String hashes = "{" + String.join(",", pending.stream().map(Pending::hash).distinct().toList()) + "}";
        Map<String, UUID> source = new HashMap<>();
        tx.workspace(ws, null, j -> {
            j.query("SELECT DISTINCT ON (content_hash) content_hash, chunk_id FROM embeddings "
                            + "WHERE model = ? AND content_hash = ANY (?::text[])",
                    rs -> { source.put(rs.getString("content_hash"), rs.getObject("chunk_id", UUID.class)); }, model, hashes);
            return null;
        });
        List<Pending> reusable = pending.stream().filter(p -> source.containsKey(p.hash())).toList();
        List<Pending> fresh = pending.stream().filter(p -> !source.containsKey(p.hash())).toList();
        if (!reusable.isEmpty()) {
            tx.workspace(ws, null, j -> j.batchUpdate(
                    "INSERT INTO embeddings (chunk_id, workspace_id, model, dim, vector, content_hash) "
                            + "SELECT ?, workspace_id, model, dim, vector, content_hash FROM embeddings WHERE chunk_id = ? AND model = ? "
                            + "ON CONFLICT DO NOTHING",
                    reusable, 200, (ps, p) -> {
                        ps.setObject(1, p.chunkId());
                        ps.setObject(2, source.get(p.hash()));
                        ps.setString(3, model);
                    }));
        }

        // 2. Embed each distinct remaining text once, outside any transaction (this is the slow, remote part).
        Map<String, String> uniqueByHash = new LinkedHashMap<>();
        for (Pending p : fresh) uniqueByHash.putIfAbsent(p.hash(), p.text());
        Map<String, float[]> vectors = new HashMap<>();
        if (!uniqueByHash.isEmpty()) {
            List<String> hashList = new ArrayList<>(uniqueByHash.keySet());
            List<float[]> embedded = provider.embed(hashList.stream().map(uniqueByHash::get).toList());
            if (embedded.size() != hashList.size()) throw new IllegalStateException("provider returned the wrong number of vectors");
            for (int i = 0; i < hashList.size(); i++) vectors.put(hashList.get(i), embedded.get(i));
        }

        // 3. Store.
        if (!fresh.isEmpty()) {
            tx.workspace(ws, null, j -> j.batchUpdate(
                    "INSERT INTO embeddings (chunk_id, workspace_id, model, dim, vector, content_hash) VALUES (?, ?, ?, ?, ?::vector, ?) "
                            + "ON CONFLICT DO NOTHING",
                    fresh, 200, (ps, p) -> {
                        ps.setObject(1, p.chunkId());
                        ps.setObject(2, ws);
                        ps.setString(3, model);
                        ps.setInt(4, provider.dimension());
                        ps.setString(5, literal(vectors.get(p.hash())));
                        ps.setString(6, p.hash());
                    }));
        }
        return new Result(total, total - pending.size(), reusable.size(), uniqueByHash.size());
    }

    /** pgvector text form: [0.1,0.2,...] */
    public static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 9).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private int count(UUID ws, String sql, Object... args) {
        Integer n = tx.workspace(ws, null, j -> j.queryForObject(sql, Integer.class, args));
        return n == null ? 0 : n;
    }
}
