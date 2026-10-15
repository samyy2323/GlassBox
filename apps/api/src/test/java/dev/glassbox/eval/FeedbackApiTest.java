package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** A thumbs-down on a real chat answer, through the inbox, into a test question. */
class FeedbackApiTest extends EvalApiSupport {
    private String ask(Account a, String question) throws Exception {
        var events = chat(a, a.workspace(), question);
        return events.stream().filter(e -> e.event().equals("done")).findFirst().orElseThrow().data().get("traceId").asText();
    }

    private JsonNode rate(Account a, String trace, String rating, String comment) throws Exception {
        return ok(send(post("/api/traces/" + trace + "/feedback"), a, "{\"rating\":\"" + rating + "\",\"comment\":" + (comment == null ? "null" : "\"" + comment + "\"") + "}"));
    }

    @Test
    void aThumbsDownBecomesATestQuestionThatTheFailureReportCanExplain() throws Exception {
        Account a = accountWithDocs(2, 5);
        String set = finalTestSet(a, "production", 6);
        String trace = ask(a, "What is the limit for part 2 of Topic0?");

        JsonNode item = rate(a, trace, "DOWN", "wrong number");
        assertThat(item.get("status").asText()).isEqualTo("OPEN");
        assertThat(item.get("question").asText()).isEqualTo("What is the limit for part 2 of Topic0?");
        assertThat(ok(send(get("/api/feedback?status=OPEN"), a, null))).hasSize(1);

        // the inbox shows what was retrieved; the person marks one of the retrieved passages as the evidence
        JsonNode detail = ok(send(get("/api/feedback/" + item.get("id").asText()), a, null));
        assertThat(detail.get("traceAvailable").asBoolean()).isTrue();
        JsonNode inContext = null;
        for (JsonNode p : detail.get("retrieved")) if (p.get("disposition").asText().equals("IN_CONTEXT")) { inContext = p; break; }
        assertThat(inContext).as("something reached the prompt").isNotNull();
        String span = "{\"documentId\":\"" + inContext.get("documentId").asText() + "\",\"charStart\":" + inContext.get("charStart").asInt() + ",\"charEnd\":" + inContext.get("charEnd").asInt() + "}";

        JsonNode diagnosed = ok(send(post("/api/feedback/" + item.get("id").asText() + "/diagnose"), a, "{\"spans\":[" + span + "]}"));
        assertThat(diagnosed.get("retrievalProblem").asBoolean()).isFalse();
        assertThat(diagnosed.get("message").asText()).contains("retrieval was not the problem");
        JsonNode blind = ok(send(post("/api/feedback/" + item.get("id").asText() + "/diagnose"), a, "{\"spans\":[]}"));
        assertThat(blind.get("message").asText()).contains("Mark the passage");

        // saving it puts a FEEDBACK question into a new draft of the chosen (final) test set
        JsonNode converted = ok(send(post("/api/feedback/" + item.get("id").asText() + "/convert"), a,
                "{\"testsetId\":\"" + set + "\",\"expectedAnswer\":\"The limit for part 2 is 42 units per hour.\",\"spans\":[" + span + "]}"));
        assertThat(converted.get("newVersion").asBoolean()).isTrue();
        assertThat(converted.get("item").get("status").asText()).isEqualTo("CONVERTED");
        assertThat(converted.get("question").get("origin").asText()).isEqualTo("FEEDBACK");
        String draft = converted.get("testsetId").asText();
        assertThat(draft).isNotEqualTo(set);
        JsonNode draftSet = ok(send(get("/api/testsets/" + draft), a, null));
        assertThat(draftSet.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draftSet.get("stats").get("fromFeedback").asInt()).isEqualTo(1);
        assertThat(count(a, "SELECT count(*) FROM test_questions WHERE origin = 'FEEDBACK' AND source_trace_id IS NOT NULL")).isEqualTo(1);

        // the share of questions that came from real failures
        JsonNode summary = ok(send(get("/api/feedback/summary"), a, null));
        assertThat(summary.get("converted").asInt()).isEqualTo(1);
        assertThat(summary.get("questionsFromFeedback").asInt()).isEqualTo(1);
        assertThat(summary.get("questionsTotal").asInt()).isGreaterThan(1);

        // the same item cannot be converted twice
        send(post("/api/feedback/" + item.get("id").asText() + "/convert"), a, "{\"testsetId\":\"" + draft + "\",\"expectedAnswer\":\"x\",\"spans\":[" + span + "]}").andExpect(status().isConflict());
    }

    @Test
    void aQuestionTheTestSetAlreadyHasIsNotAddedTwice() throws Exception {
        Account a = accountWithDocs(2, 5);
        String set = finalTestSet(a, "dedupe", 6);
        JsonNode first = ok(send(get("/api/testsets/" + set + "/questions?limit=5"), a, null)).get("items").get(0);
        String asked = first.get("question").asText();
        String trace = ask(a, asked);
        JsonNode item = rate(a, trace, "DOWN", null);
        JsonNode ev = first.get("evidence").get(0);
        String span = "{\"documentId\":\"" + ev.get("documentId").asText() + "\",\"charStart\":" + ev.get("charStart").asInt() + ",\"charEnd\":" + ev.get("charEnd").asInt() + "}";
        send(post("/api/feedback/" + item.get("id").asText() + "/convert"), a, "{\"testsetId\":\"" + set + "\",\"expectedAnswer\":\"a\",\"spans\":[" + span + "]}")
                .andExpect(status().isConflict());
        assertThat(count(a, "SELECT count(*) FROM testsets")).as("the refusal did not make a new version").isEqualTo(1);
        assertThat(ok(send(post("/api/feedback/" + item.get("id").asText() + "/dismiss"), a, null)).get("status").asText()).isEqualTo("DISMISSED");
    }

    @Test
    void aQuestionWithNoAnswerInTheDocumentsCanBeSavedAsUnanswerableButAnythingElseNeedsEvidence() throws Exception {
        Account a = accountWithDocs(1, 4);
        String set = finalTestSet(a, "noanswer", 4);
        String trace = ask(a, "Who won the football match yesterday?");
        JsonNode item = rate(a, trace, "DOWN", "it made something up");
        send(post("/api/feedback/" + item.get("id").asText() + "/convert"), a, "{\"testsetId\":\"" + set + "\",\"qtype\":\"FACTUAL\",\"expectedAnswer\":\"x\"}").andExpect(status().isBadRequest());
        JsonNode done = ok(send(post("/api/feedback/" + item.get("id").asText() + "/convert"), a, "{\"testsetId\":\"" + set + "\"}"));
        assertThat(done.get("question").get("qtype").asText()).isEqualTo("UNANSWERABLE");
        assertThat(done.get("question").get("evidence")).isEmpty();
    }

    @Test
    void ratingAgainChangesTheRatingPraiseNeedsNoTriageAndOnlyChatAnswersCanBeRated() throws Exception {
        Account a = accountWithDocs(1, 4);
        String trace = ask(a, "What is the limit for part 1 of Topic0?");
        assertThat(rate(a, trace, "UP", null).get("status").asText()).isEqualTo("DISMISSED");
        JsonNode down = rate(a, trace, "DOWN", "actually wrong");
        assertThat(down.get("status").asText()).isEqualTo("OPEN");
        assertThat(count(a, "SELECT count(*) FROM feedback")).as("one rating per person per answer").isEqualTo(1);
        send(post("/api/traces/" + trace + "/feedback"), a, "{\"rating\":\"MEH\"}").andExpect(status().isBadRequest());
        send(post("/api/traces/00000000-0000-0000-0000-000000000000/feedback"), a, "{\"rating\":\"UP\"}").andExpect(status().isNotFound());
    }

    @Test
    void viewersCanRateButOnlyEditorsTriageAndOtherWorkspacesSeeNothing() throws Exception {
        Account owner = accountWithDocs(1, 4);
        Account viewer = register();
        mvc.perform(post("/api/workspaces/" + owner.workspace() + "/members").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        Account asViewer = new Account(viewer.token(), owner.workspace(), viewer.email());
        String trace = ask(asViewer, "What is the limit for part 1 of Topic0?");
        JsonNode item = rate(asViewer, trace, "DOWN", null);
        send(get("/api/feedback"), asViewer, null).andExpect(status().isForbidden());
        send(get("/api/feedback/" + item.get("id").asText()), asViewer, null).andExpect(status().isForbidden());
        assertThat(ok(send(get("/api/feedback"), owner, null))).hasSize(1);

        Account stranger = register();
        assertThat(ok(send(get("/api/feedback"), stranger, null))).isEmpty();
        send(get("/api/feedback/" + item.get("id").asText()), stranger, null).andExpect(status().isNotFound());
        send(post("/api/traces/" + trace + "/feedback"), stranger, "{\"rating\":\"DOWN\"}").andExpect(status().isNotFound());
        List<String> none = new java.util.ArrayList<>();
        ok(send(get("/api/feedback/summary"), stranger, null)).fieldNames().forEachRemaining(none::add);
        assertThat(ok(send(get("/api/feedback/summary"), stranger, null)).get("open").asInt()).isZero();
    }
}
