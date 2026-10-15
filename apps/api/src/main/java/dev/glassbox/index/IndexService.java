package dev.glassbox.index;

import dev.glassbox.chunking.Chunker;
import dev.glassbox.embed.EmbeddingProviders;
import dev.glassbox.embed.EmbeddingService;
import dev.glassbox.ingest.DocumentIndexer;
import dev.glassbox.ingest.DocumentStorage;
import dev.glassbox.parse.ParsedDocument;
import dev.glassbox.retrieval.Chunking;
import dev.glassbox.retrieval.ChunkingResolver;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.tenancy.Tx;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Makes sure a (chunking, embedding model) pair exists for every current document of a workspace. Experiments and AutoTune call it
 * before they search, because a comparison on a half-indexed corpus would compare corpora, not pipelines.
 *
 * Uploads create only the default chunking with the default model. Anything else is built here, on demand, from the stored files:
 * parse, chunk, embed. It is idempotent and resumable: a document that already has the chunks and every vector is skipped, vectors of
 * text that was embedded before are copied, and a crash part-way only repeats the document it was on.
 */
@Service
public class IndexService {
    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    /** One thing to build. {@code needsChunks} is false when only some vectors are missing. */
    public record Missing(UUID documentId, UUID versionId, String title, String sourceType, String storagePath, boolean needsChunks) {}

    public record Need(Chunking chunking, String model) {}

    public record Plan(Need need, List<Missing> missing) {}

    private final Tx tx;
    private final ChunkingResolver chunkings;
    private final DocumentIndexer indexer;
    private final DocumentStorage storage;
    private final EmbeddingService embeddings;
    private final EmbeddingProviders providers;

    public IndexService(Tx tx, ChunkingResolver chunkings, DocumentIndexer indexer, DocumentStorage storage, EmbeddingService embeddings,
                        EmbeddingProviders providers) {
        this.tx = tx;
        this.chunkings = chunkings;
        this.indexer = indexer;
        this.storage = storage;
        this.embeddings = embeddings;
        this.providers = providers;
    }

    /** The distinct pairs a list of configurations searches. */
    public Set<Need> needs(List<PipelineConfig> configs) {
        Set<Need> out = new LinkedHashSet<>();
        for (PipelineConfig c : configs) out.add(new Need(c.chunking(), c.retrieval().model()));
        return out;
    }

    public Chunker chunkerOf(Chunking chunking) {
        return chunking == null ? chunkings.defaultChunker() : chunking.chunker();
    }

    /** What is not built yet for this pair. Empty when the pair is complete. */
    public Plan plan(UUID ws, Need need) {
        Chunker chunker = chunkerOf(need.chunking());
        UUID chunkingId = chunkings.resolve(ws, need.chunking()).orElse(null);
        List<Missing> missing = tx.workspace(ws, null, j -> j.query(
                "SELECT d.id AS document_id, v.id AS version_id, d.title, d.source_type, v.storage_path, "
                        + "(CAST(? AS uuid) IS NULL OR NOT EXISTS (SELECT 1 FROM chunks c WHERE c.document_version_id = v.id AND c.chunking_id = CAST(? AS uuid) AND NOT c.superseded)) AS needs_chunks "
                        + "FROM documents d JOIN document_versions v ON v.document_id = d.id AND v.version = d.version "
                        + "WHERE d.status = 'READY' AND d.superseded_by IS NULL AND ("
                        + "  CAST(? AS uuid) IS NULL OR NOT EXISTS (SELECT 1 FROM chunks c WHERE c.document_version_id = v.id AND c.chunking_id = CAST(? AS uuid) AND NOT c.superseded)"
                        + "  OR EXISTS (SELECT 1 FROM chunks c WHERE c.document_version_id = v.id AND c.chunking_id = CAST(? AS uuid) AND NOT c.superseded AND c.role <> 'PARENT' "
                        + "     AND NOT EXISTS (SELECT 1 FROM embeddings e WHERE e.chunk_id = c.id AND e.model = ?))) "
                        + "ORDER BY d.created_at, d.id",
                (rs, n) -> new Missing(rs.getObject("document_id", UUID.class), rs.getObject("version_id", UUID.class), rs.getString("title"),
                        rs.getString("source_type"), rs.getString("storage_path"), rs.getBoolean("needs_chunks")),
                chunkingId, chunkingId, chunkingId, chunkingId, chunkingId, need.model()));
        log.debug("index plan for {} {}: {} documents ({} strategy)", chunker.strategy(), need.model(), missing.size(), chunker.strategy());
        return new Plan(need, missing);
    }

    /** Builds everything {@link #plan} lists. {@code alive} is called after each document so a long job can keep its lock. */
    public int ensure(UUID ws, Need need, Runnable alive) throws Exception {
        providers.get(need.model()); // fails fast when the model has no provider here
        Plan plan = plan(ws, need);
        Chunker chunker = chunkerOf(need.chunking());
        for (Missing m : plan.missing()) {
            UUID chunkingId;
            if (m.needsChunks()) {
                ParsedDocument parsed = indexer.parse(m.sourceType(), storage.read(m.storagePath()));
                chunkingId = indexer.persist(ws, m.documentId(), m.versionId(), parsed, chunker);
            } else {
                chunkingId = chunkings.resolve(ws, need.chunking()).orElseThrow();
            }
            var r = embeddings.embedChunks(ws, m.versionId(), chunkingId, need.model());
            log.info("indexed {} with {} / {}: {} chunks, {} texts embedded", m.title(), chunker.strategy(), need.model(), r.chunks(), r.textsEmbedded());
            alive.run();
        }
        return plan.missing().size();
    }

    /** Human-readable line for an estimate: what building this pair would involve. */
    public String describe(UUID ws, Need need) {
        int n = plan(ws, need).missing().size();
        String name = need.chunking() == null ? "the default chunking" : need.chunking().label();
        return n == 0 ? null : "Indexing: " + n + " document(s) still need " + name + " with " + need.model() + " before this can run.";
    }
}
