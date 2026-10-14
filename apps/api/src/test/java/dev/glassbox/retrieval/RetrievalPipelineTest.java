package dev.glassbox.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.TestFiles;
import dev.glassbox.ingest.ApiTestSupport;
import dev.glassbox.rerank.Reranker;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Upload -> ingest -> embed -> search -> rerank -> context, through the real tables, with RLS. */
class RetrievalPipelineTest extends ApiTestSupport {
    @Autowired RetrievalPipeline pipeline;

    static final String MODEL = "bge-small-en-v1.5";

    JsonNode md(Account a, String name, String text) throws Exception {
        return uploadOk(a, name, text.getBytes(StandardCharsets.UTF_8));
    }

    /** A workspace with three topically distinct documents, ingested and embedded. */
    record Corpus(Account account, UUID pumps, UUID canteen, UUID safety) {}

    Corpus corpus() throws Exception {
        Account a = register();
        String salt = UUID.randomUUID().toString();
        UUID pumps = UUID.fromString(md(a, "pumps.md", "# Pump maintenance\n\nAll pumps must be inspected every ninety days by a certified engineer. " + salt).get("id").asText());
        UUID canteen = UUID.fromString(md(a, "canteen.md", "# Cafeteria\n\nThe cafeteria opens at eight in the morning and serves breakfast and lunch. " + salt).get("id").asText());
        UUID safety = UUID.fromString(md(a, "safety.md", "# Fire safety\n\nFire extinguishers are checked monthly and evacuation drills happen twice a year. " + salt).get("id").asText());
        drainQueue();
        return new Corpus(a, pumps, canteen, safety);
    }

    PipelineConfig config() {
        return PipelineConfig.defaults(MODEL);
    }

    @Test
    void hybridSearchFindsTheRightDocumentAndExplainsWhy() throws Exception {
        Corpus c = corpus();
        var r = pipeline.retrieve(c.account().workspace(), "how often are pumps inspected", config());

        assertThat(r.candidates()).isNotEmpty();
        Candidate top = r.candidates().get(0);
        assertThat(top.documentId()).isEqualTo(c.pumps());
        assertThat(top.vecRank()).isEqualTo(1);
        assertThat(top.kwRank()).isEqualTo(1);
        assertThat(top.fusedScore()).isGreaterThan(r.candidates().get(1).fusedScore());

        assertThat(r.spans()).extracting(Span::stage).containsExactly("query_embedding", "retrieval", "rerank", "context");
        assertThat(r.context().included().get(0).label()).isEqualTo("c1");
        assertThat(r.context().included().get(0).chunk().documentId()).isEqualTo(c.pumps());
        assertThat(r.context().tokensUsed()).isPositive().isLessThanOrEqualTo(3000);
    }

    @Test
    void modesReturnOnlyWhatTheirRetrieverFinds() throws Exception {
        Corpus c = corpus();
        var vector = pipeline.retrieve(c.account().workspace(), "pumps inspected", config().withMode(RetrievalParams.Mode.VECTOR));
        assertThat(vector.candidates()).hasSize(3).allSatisfy(x -> assertThat(x.kwRank()).isNull());
        var keyword = pipeline.retrieve(c.account().workspace(), "pumps inspected", config().withMode(RetrievalParams.Mode.KEYWORD));
        assertThat(keyword.candidates()).singleElement().satisfies(x -> {
            assertThat(x.documentId()).isEqualTo(c.pumps());
            assertThat(x.vecRank()).isNull();
        });
    }

    @Test
    void crossEncoderRerankingReordersAndTruncatesToTopN() throws Exception {
        Corpus c = corpus();
        var r = pipeline.retrieve(c.account().workspace(), "fire extinguishers checked monthly",
                config().withReranker(Reranker.Type.CROSS_ENCODER).withTopN(2));
        assertThat(r.ranked()).hasSize(2);
        assertThat(r.ranked().get(0).documentId()).isEqualTo(c.safety());
        assertThat(r.ranked()).allSatisfy(x -> assertThat(x.rerankScore()).isNotNull());
        assertThat(r.ranked().get(0).rerankScore()).isGreaterThanOrEqualTo(r.ranked().get(1).rerankScore());
        assertThat(r.context().included()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void tokenBudgetDropsChunksAndRecordsIt() throws Exception {
        Corpus c = corpus();
        var r = pipeline.retrieve(c.account().workspace(), "pumps cafeteria fire", config().withTokenBudget(100).withTopN(3));
        assertThat(r.context().tokensUsed()).isLessThanOrEqualTo(100);
        // Each chunk is ~25-30 tokens, so at most 3 fit in 100, and anything not included is accounted for.
        assertThat(r.context().included().size() + r.context().dropped().size()).isEqualTo(r.ranked().size());
    }

    @Test
    void anEmptyWorkspaceReturnsAnEmptyResultNotAnError() throws Exception {
        Account fresh = register();
        var r = pipeline.retrieve(fresh.workspace(), "anything", config());
        assertThat(r.candidates()).isEmpty();
        assertThat(r.context().included()).isEmpty();
        assertThat(r.chunkingId()).isNull();
    }

    @Test
    void otherTenantsNeverSeeMyChunks() throws Exception {
        Corpus mine = corpus();
        Corpus theirs = corpus(); // an equally rich corpus in another workspace
        var r = pipeline.retrieve(theirs.account().workspace(), "how often are pumps inspected", config());
        assertThat(r.candidates()).isNotEmpty();
        assertThat(r.candidates()).allSatisfy(x -> assertThat(x.documentId()).isIn(theirs.pumps(), theirs.canteen(), theirs.safety()));
        assertThat(r.candidates()).noneMatch(x -> x.documentId().equals(mine.pumps()));
    }

    @Test
    void replacedDocumentsOldChunksAreNoLongerRetrievable() throws Exception {
        Account a = register();
        String oldMarker = "oldmarkerzeta" + UUID.randomUUID().toString().replace("-", "");
        JsonNode d = md(a, "policy.md", "# Policy\n\nVisitors sign in at reception. " + oldMarker);
        drainQueue();
        assertThat(pipeline.retrieve(a.workspace(), oldMarker, config()).candidates()).isNotEmpty();

        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/documents/" + d.get("id").asText())
                .file(new org.springframework.mock.web.MockMultipartFile("file", "policy.md", null,
                        "# Policy\n\nVisitors sign in at reception and wear a badge. newmarkerone".getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()));
        drainQueue();

        var after = pipeline.retrieve(a.workspace(), "visitors sign in reception badge", config());
        assertThat(after.candidates()).hasSize(1);
        assertThat(after.candidates().get(0).text()).contains("newmarkerone").doesNotContain(oldMarker);
        assertThat(pipeline.retrieve(a.workspace(), oldMarker, config().withMode(RetrievalParams.Mode.KEYWORD)).candidates()).isEmpty();
    }

    // ------------------------------------------------------------ HTTP API

    private org.springframework.test.web.servlet.ResultActions search(Account a, UUID ws, String body) throws Exception {
        return mvc.perform(post("/api/search").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", ws)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void searchEndpointReturnsCandidatesSpansAndContext() throws Exception {
        Corpus c = corpus();
        JsonNode r = json.readTree(search(c.account(), c.account().workspace(), "{\"query\":\"how often are pumps inspected\",\"topN\":3}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(r.get("candidates").get(0).get("documentId").asText()).isEqualTo(c.pumps().toString());
        assertThat(r.get("candidates").get(0).has("vecRank")).isTrue();
        assertThat(r.get("spans")).extracting(n -> n.get("stage").asText()).containsExactly("query_embedding", "retrieval", "rerank", "context");
        assertThat(r.get("context").get("included").get(0).get("label").asText()).isEqualTo("c1");
    }

    @Test
    void searchEndpointEnforcesRolesAndValidation() throws Exception {
        Corpus c = corpus();
        Account viewer = register();
        mvc.perform(post("/api/workspaces/" + c.account().workspace() + "/members").header("Authorization", "Bearer " + c.account().token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        Account outsider = register();

        search(viewer, c.account().workspace(), "{\"query\":\"pumps\"}").andExpect(status().isOk());
        search(outsider, c.account().workspace(), "{\"query\":\"pumps\"}").andExpect(status().isNotFound());
        search(c.account(), c.account().workspace(), "{\"query\":\"\"}").andExpect(status().isBadRequest());
        search(c.account(), c.account().workspace(), "{\"query\":\"x\",\"topN\":500}").andExpect(status().isBadRequest());
        mvc.perform(post("/api/search").contentType(MediaType.APPLICATION_JSON).content("{\"query\":\"x\"}")).andExpect(status().isUnauthorized());
    }
}
