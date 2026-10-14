package dev.glassbox.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.FakeLlmProvider;
import dev.glassbox.ingest.ApiTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** Every chat outcome leaves a trace that X-Ray can read back. */
class TraceApiTest extends ApiTestSupport {
    @Autowired TraceService traceService;

    JsonNode getTrace(Account a, String id) throws Exception {
        return json.readTree(mvc.perform(get("/api/traces/" + id).header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    String traceIdOf(List<Sse> events) {
        Sse last = events.get(events.size() - 1);
        assertThat(last.data().has("traceId")).as("the %s event carries the trace id", last.event()).isTrue();
        return last.data().get("traceId").asText();
    }

    @Test
    void anAnsweredQuestionIsTracedWithCandidatesPromptAndCitations() throws Exception {
        Account a = accountWithDoc("# Pumps\n\nAll pumps must be inspected every ninety days by a certified engineer. " + UUID.randomUUID());
        List<Sse> events = chat(a, a.workspace(), "how often are pumps inspected");
        JsonNode t = getTrace(a, traceIdOf(events));

        assertThat(t.get("kind").asText()).isEqualTo("CHAT");
        assertThat(t.get("status").asText()).isEqualTo("OK");
        assertThat(t.get("question").asText()).isEqualTo("how often are pumps inspected");
        assertThat(t.get("answer").asText()).contains("[c1]");
        assertThat(t.get("model").asText()).isEqualTo("fake-llm");
        assertThat(t.get("latencyMs").asInt()).isPositive();
        assertThat(t.get("spans")).extracting(s -> s.get("stage").asText()).containsExactly("query_embedding", "retrieval", "rerank", "context", "generation");

        JsonNode data = t.get("data");
        JsonNode first = data.get("candidates").get(0);
        assertThat(first.get("title").asText()).isEqualTo("doc");
        assertThat(first.get("disposition").asText()).isEqualTo("IN_CONTEXT");
        assertThat(first.get("label").asText()).isEqualTo("c1");
        assertThat(first.get("vecRank").asInt()).isEqualTo(1);
        assertThat(first.get("kwRank").asInt()).isEqualTo(1);
        assertThat(first.get("snippet").asText()).contains("inspected every ninety days");
        assertThat(first.get("charEnd").asInt()).isGreaterThan(first.get("charStart").asInt());

        assertThat(data.get("config").get("topN").asInt()).isEqualTo(5);
        assertThat(data.get("context").get("included")).hasSize(1);
        assertThat(data.get("context").get("budget").asInt()).isEqualTo(2000);
        assertThat(data.get("prompt")).extracting(m -> m.get("role").asText()).containsExactly("system", "user");
        assertThat(data.get("prompt").get(1).get("content").asText()).contains("<context>").contains("Question: how often are pumps inspected");
        assertThat(data.get("citations").get(0).get("label").asText()).isEqualTo("c1");
        assertThat(data.get("usage").get("promptTokens").asInt()).isPositive();
    }

    @Test
    void theListCanBeLimitedToChatConversations() throws Exception {
        Account a = accountWithDoc("# Pumps\n\nAll pumps must be inspected every ninety days. " + UUID.randomUUID());
        chat(a, a.workspace(), "how often are pumps inspected");
        traceService.save(a.workspace(), null, "EXPERIMENT", dev.glassbox.trace.TraceModel.Status.OK, "an experiment answer", "answer", "fake-llm", 5, List.of(),
                new dev.glassbox.trace.TraceModel.TraceData(null, List.of(), null, null, List.of(), List.of(), null, null));

        JsonNode all = json.readTree(mvc.perform(get("/api/traces").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode chats = json.readTree(mvc.perform(get("/api/traces?kind=CHAT").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode experiments = json.readTree(mvc.perform(get("/api/traces?kind=EXPERIMENT").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(all.get("items")).hasSize(2);
        assertThat(chats.get("items")).hasSize(1);
        assertThat(chats.get("items").get(0).get("kind").asText()).isEqualTo("CHAT");
        assertThat(experiments.get("items")).hasSize(1);
        assertThat(experiments.get("items").get(0).get("question").asText()).isEqualTo("an experiment answer");
        mvc.perform(get("/api/traces?kind=NOPE").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace())).andExpect(status().isBadRequest());
    }

    @Test
    void anAbstainedQuestionIsTracedWithoutAPrompt() throws Exception {
        Account empty = register();
        JsonNode t = getTrace(empty, traceIdOf(chat(empty, empty.workspace(), "anything")));
        assertThat(t.get("status").asText()).isEqualTo("ABSTAINED");
        assertThat(t.get("data").get("candidates")).isEmpty();
        assertThat(t.get("data").has("prompt")).as("no model call, no prompt").isFalse();
        assertThat(t.get("spans")).extracting(s -> s.get("stage").asText()).contains("generation");
    }

    @Test
    void aFailedQuestionIsTracedWithItsErrorAndWhateverWasRetrieved() throws Exception {
        Account a = accountWithDoc("# Pumps\n\nPumps are inspected every ninety days. " + UUID.randomUUID());
        FakeLlmProvider.FAIL_NEXT.set(1);
        List<Sse> events = chat(a, a.workspace(), "how often are pumps inspected");
        assertThat(names(events)).contains("error");
        JsonNode t = getTrace(a, traceIdOf(events));
        assertThat(t.get("status").asText()).isEqualTo("ERROR");
        assertThat(t.get("data").get("error").asText()).contains("unreachable");
        assertThat(t.get("data").get("candidates")).isNotEmpty();
        assertThat(t.get("data").get("prompt")).as("the prompt that failed is kept for diagnosis").hasSize(2);
    }

    @Test
    void tracesAreWorkspacePrivateAndVisibleToViewers() throws Exception {
        Account owner = accountWithDoc("# Pumps\n\nPumps are inspected every ninety days. " + UUID.randomUUID());
        String id = traceIdOf(chat(owner, owner.workspace(), "pumps inspected"));
        Account viewer = register();
        Account outsider = register();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workspaces/" + owner.workspace() + "/members")
                .header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());

        mvc.perform(get("/api/traces/" + id).header("Authorization", "Bearer " + viewer.token()).header("X-Workspace-Id", owner.workspace()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/traces/" + id).header("Authorization", "Bearer " + outsider.token()).header("X-Workspace-Id", owner.workspace()))
                .andExpect(status().isNotFound());
        // Right workspace header, wrong tenant: the row is invisible, not forbidden.
        mvc.perform(get("/api/traces/" + id).header("Authorization", "Bearer " + outsider.token()).header("X-Workspace-Id", outsider.workspace()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/traces/" + UUID.randomUUID()).header("Authorization", "Bearer " + owner.token()).header("X-Workspace-Id", owner.workspace()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/traces/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void theListIsNewestFirstAndCursorPaginated() throws Exception {
        Account a = accountWithDoc("# Pumps\n\nPumps are inspected every ninety days. " + UUID.randomUUID());
        String t1 = traceIdOf(chat(a, a.workspace(), "first question about pumps"));
        String t2 = traceIdOf(chat(a, a.workspace(), "second question about pumps"));
        String t3 = traceIdOf(chat(a, a.workspace(), "third question about pumps"));

        JsonNode p1 = json.readTree(mvc.perform(get("/api/traces?limit=2").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()))
                .andReturn().getResponse().getContentAsString());
        assertThat(p1.get("items")).extracting(n -> n.get("id").asText()).containsExactly(t3, t2);
        assertThat(p1.get("items").get(0).has("data")).as("summaries are light").isFalse();
        JsonNode p2 = json.readTree(mvc.perform(get("/api/traces").param("limit", "2").param("cursor", p1.get("nextCursor").asText())
                .header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace())).andReturn().getResponse().getContentAsString());
        assertThat(p2.get("items")).extracting(n -> n.get("id").asText()).containsExactly(t1);
        assertThat(p2.has("nextCursor")).isFalse();
    }

    @Test
    void oldTracesAreDeletedWhenNewOnesAreSavedAndOnlyInTheSameWorkspace() throws Exception {
        Account mine = register();
        Account theirs = register();
        UUID oldMine = UUID.randomUUID(), oldTheirs = UUID.randomUUID();
        for (var x : List.of(new Object[] {mine, oldMine}, new Object[] {theirs, oldTheirs})) {
            Account acc = (Account) x[0];
            UUID id = (UUID) x[1];
            tx.workspace(acc.workspace(), null, j -> j.update("INSERT INTO traces (id, workspace_id, kind, question, created_at) "
                    + "VALUES (?, ?, 'CHAT', 'old', now() - interval '90 days')", id, acc.workspace()));
        }
        // The purge runs on every 50th save per process; make sure this loop crosses one.
        for (int i = 0; i < 50; i++) {
            traceService.save(mine.workspace(), null, "CHAT", TraceModel.Status.OK, "q" + i, "a", "m", 1, List.of(),
                    new TraceModel.TraceData(null, List.of(), null, null, List.of(), List.of(), null, null));
        }
        List<java.util.Map<String, Object>> mineLeft = tx.workspace(mine.workspace(), null, j -> j.queryForList("SELECT id FROM traces WHERE id = ?", oldMine));
        List<java.util.Map<String, Object>> theirsLeft = tx.workspace(theirs.workspace(), null, j -> j.queryForList("SELECT id FROM traces WHERE id = ?", oldTheirs));
        assertThat(mineLeft).as("purged").isEmpty();
        assertThat(theirsLeft).as("another workspace is untouched (RLS)").hasSize(1);
    }
}
