package dev.glassbox.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.FakeEmbeddingProvider;
import dev.glassbox.TestFiles;
import dev.glassbox.embed.EmbeddingService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Ingestion embeds every chunk, reuses vectors, survives provider outages and respects tenancy. */
class EmbeddingIngestTest extends ApiTestSupport {
    static final String MODEL = "bge-small-en-v1.5";

    @Autowired EmbeddingService embeddingService;

    @BeforeEach
    void resetFake() {
        FakeEmbeddingProvider.FAIL_NEXT.set(0);
    }

    JsonNode uploadMd(Account a, String name, String markdown) throws Exception {
        return uploadOk(a, name, markdown.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void everyChunkGetsAVectorOfTheModelDimension() throws Exception {
        Account a = register();
        JsonNode d = uploadOk(a, "manual.pdf", TestFiles.pdf(pdfText(), "Second page about valves."));
        drainQueue();
        assertThat(doc(a, d.get("id").asText()).get("status").asText()).isEqualTo("READY");

        int chunks = countRows(a, "chunks");
        assertThat(chunks).isPositive();
        assertThat(countRows(a, "embeddings")).isEqualTo(chunks);
        var rows = tx.workspace(a.workspace(), null, j -> j.queryForList("SELECT DISTINCT model, dim, vector_dims(vector) AS dims FROM embeddings"));
        assertThat(rows).containsExactly(Map.of("model", MODEL, "dim", 384, "dims", 384));
    }

    @Test
    void nearestNeighbourQueryUsesTheIndexedCastAndFindsTheRightChunk() throws Exception {
        Account a = register();
        JsonNode pumps = uploadMd(a, "pumps.md", "Pumps must be inspected every ninety days by a certified engineer. " + UUID.randomUUID());
        uploadMd(a, "canteen.md", "The cafeteria opens at eight in the morning and serves breakfast. " + UUID.randomUUID());
        drainQueue();

        String query = EmbeddingService.literal(FakeEmbeddingProvider.vector("how often are pumps inspected ninety days", 384));
        UUID nearestDoc = tx.workspace(a.workspace(), null, j -> j.queryForObject(
                "SELECT c.document_id FROM embeddings e JOIN chunks c ON c.id = e.chunk_id WHERE e.model = ? "
                        + "ORDER BY e.vector::vector(384) <=> ?::vector(384) LIMIT 1", UUID.class, MODEL, query));
        assertThat(nearestDoc).isEqualTo(UUID.fromString(pumps.get("id").asText()));
    }

    @Test
    void reRunningEmbeddingIsANoOpAndIdenticalTextReusesStoredVectors() throws Exception {
        Account a = register();
        JsonNode d = uploadMd(a, "policy.md", "Spare parts are ordered every quarter. " + UUID.randomUUID());
        drainQueue();
        UUID docId = UUID.fromString(d.get("id").asText());
        record Ids(UUID version, UUID chunking) {}
        Ids ids = tx.workspace(a.workspace(), null, j -> j.queryForObject(
                "SELECT document_version_id, chunking_id FROM chunks WHERE document_id = ? LIMIT 1",
                (rs, n) -> new Ids(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), docId));

        int before = FakeEmbeddingProvider.TEXTS_EMBEDDED.get();
        var again = embeddingService.embedChunks(a.workspace(), ids.version(), ids.chunking(), MODEL);
        assertThat(again.alreadyEmbedded()).isEqualTo(again.chunks());
        assertThat(FakeEmbeddingProvider.TEXTS_EMBEDDED.get()).as("nothing re-embedded").isEqualTo(before);

        // Add a chunk with identical text: it must copy the stored vector instead of calling the provider.
        tx.workspace(a.workspace(), null, j -> j.update(
                "INSERT INTO chunks (workspace_id, document_id, document_version_id, chunking_id, ordinal, text, token_count, char_start, char_end) "
                        + "SELECT workspace_id, document_id, document_version_id, chunking_id, ordinal + 1000, text, token_count, char_start, char_end "
                        + "FROM chunks WHERE document_id = ?", docId));
        var reused = embeddingService.embedChunks(a.workspace(), ids.version(), ids.chunking(), MODEL);
        assertThat(reused.reused()).isEqualTo(1);
        assertThat(reused.textsEmbedded()).isZero();
        assertThat(FakeEmbeddingProvider.TEXTS_EMBEDDED.get()).isEqualTo(before);
        assertThat(countRows(a, "embeddings")).isEqualTo(countRows(a, "chunks"));
    }

    @Test
    void providerOutageFailsTheJobWhichRetriesAndThenSucceeds() throws Exception {
        Account a = register();
        JsonNode d = uploadMd(a, "retry.md", "Fire drills happen twice a year. " + UUID.randomUUID());
        FakeEmbeddingProvider.FAIL_NEXT.set(1);

        assertThat(worker.runOnce("w")).isTrue();
        assertThat(doc(a, d.get("id").asText()).get("status").asText()).isEqualTo("PROCESSING");
        assertThat(countJobs("WHERE status = 'QUEUED' AND attempts = 1")).isEqualTo(1);
        assertThat(countRows(a, "embeddings")).isZero();

        tx.worker(j -> j.update("UPDATE jobs SET run_after = now()")); // skip the backoff wait
        assertThat(worker.runOnce("w")).isTrue();
        assertThat(doc(a, d.get("id").asText()).get("status").asText()).isEqualTo("READY");
        assertThat(countRows(a, "embeddings")).isEqualTo(countRows(a, "chunks"));
    }

    @Test
    void embeddingsAreTenantIsolatedAndDeletedWithTheDocument() throws Exception {
        Account a = register();
        Account b = register();
        JsonNode d = uploadMd(a, "mine.md", "Confidential merger plan " + UUID.randomUUID());
        drainQueue();
        assertThat(countRows(a, "embeddings")).isPositive();
        assertThat(countRows(b, "embeddings")).as("other tenant sees no vectors").isZero();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/documents/" + d.get("id").asText())
                .header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()));
        assertThat(countRows(a, "embeddings")).as("cascade on delete").isZero();
    }

    @Test
    void replacingADocumentEmbedsTheNewVersionAndKeepsOldVectorsForSupersededChunks() throws Exception {
        Account a = register();
        JsonNode d = uploadOk(a, "v.pdf", TestFiles.pdf(pdfText()));
        drainQueue();
        int v1 = countRows(a, "embeddings");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart(org.springframework.http.HttpMethod.PUT, "/api/documents/" + d.get("id").asText())
                .file(new org.springframework.mock.web.MockMultipartFile("file", "v.pdf", null, TestFiles.pdf(pdfText() + " Updated.")))
                .header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()));
        drainQueue();
        assertThat(countRows(a, "embeddings")).isGreaterThan(v1);
        assertThat(countRows(a, "embeddings")).isEqualTo(countRows(a, "chunks"));
    }
}
