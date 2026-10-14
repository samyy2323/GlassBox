package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.FakeLlmProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Full experiments: the model writes the answers, claims are verified, a judge grades, abstention is scored, every answer has a trace. */
class FullExperimentApiTest extends EvalApiSupport {
    static final String TWO = "[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"},{\"name\":\"vector\",\"mode\":\"VECTOR\"}]";

    /** The fake model answers with two sentences copied from the retrieved context, so the claims in the answer are checkable. */
    static void answerFromContext() {
        java.util.regex.Pattern sentence = java.util.regex.Pattern.compile("The \\w+ unit controls the \\w+ circuit and retries every failed task for the plant\\.");
        FakeLlmProvider.ANSWER_SCRIPT = user -> {
            var m = sentence.matcher(user);
            return m.find() ? m.group() + " [c1] The system reports its health to operators every day. [c1]" : null;
        };
    }

    JsonNode createFull(Account a, String testset, String name, String configs) throws Exception {
        return ok(send(post("/api/experiments"), a, "{\"testsetId\":\"" + testset + "\",\"name\":\"" + name + "\",\"mode\":\"FULL\",\"configs\":" + configs + "}"));
    }

    /** A FINAL test set with the generated questions plus the given manual ones (unanswerable, or answerable but told to abstain). */
    String finalWithManual(Account a, String name, int count, boolean unanswerable, boolean answerableAbstained) throws Exception {
        String id = ok(send(post("/api/testsets/generate"), a, "{\"name\":\"" + name + "\",\"count\":" + count + ",\"seed\":11,\"mix\":{\"FACTUAL\":1}}")).get("testset").get("id").asText();
        drainQueue();
        JsonNode firstEvidence = null;
        for (JsonNode q : ok(send(get("/api/testsets/" + id + "/questions?limit=200"), a, null)).get("items")) {
            ok(send(patch("/api/questions/" + q.get("id").asText()), a, "{\"reviewStatus\":\"ACCEPTED\"}"));
            if (firstEvidence == null) firstEvidence = q.get("evidence").get(0);
        }
        if (unanswerable) {
            ok(send(post("/api/testsets/" + id + "/questions"), a, "{\"question\":\"What colour is the CEO's car? ABSTAIN-PLEASE\",\"expectedAnswer\":\"Not stated\",\"qtype\":\"UNANSWERABLE\"}"));
        }
        if (answerableAbstained) {
            ok(send(post("/api/testsets/" + id + "/questions"), a, "{\"question\":\"What limit applies to the first part? ABSTAIN-PLEASE\",\"expectedAnswer\":\"40 units per hour\",\"qtype\":\"NUMERIC\","
                    + "\"spans\":[{\"documentId\":\"" + firstEvidence.get("documentId").asText() + "\",\"charStart\":" + firstEvidence.get("charStart").asInt() + ",\"charEnd\":"
                    + firstEvidence.get("charEnd").asInt() + "}]}"));
        }
        ok(send(post("/api/testsets/" + id + "/finalize"), a, "{}"));
        return id;
    }

    static JsonNode metricOrNull(JsonNode run, String metric, String slice) {
        for (JsonNode m : run.get("metrics")) if (m.get("metric").asText().equals(metric) && m.get("slice").asText().equals(slice) && m.get("method").asText().equals("question")) return m;
        return null;
    }

    @Test
    void aFullRunAnswersVerifiesJudgesAndKeepsATraceForEveryQuestion() throws Exception {
        answerFromContext();
        Account a = accountWithDocs(2, 6);
        String set = finalTestSet(a, "full", 4);
        JsonNode estimate = ok(send(post("/api/experiments/estimate"), a, "{\"testsetId\":\"" + set + "\",\"mode\":\"FULL\",\"configs\":" + TWO + "}"));
        int questions = estimate.get("questions").asInt();
        assertThat(estimate.get("llmCalls").asInt()).isEqualTo(questions * 2 * 5);
        assertThat(estimate.get("estimatedTokens").asLong()).isPositive();
        assertThat(estimate.get("notes").toString()).contains("judge");

        JsonNode created = createFull(a, set, "answers", TWO);
        assertThat(created.get("mode").asText()).isEqualTo("FULL");
        assertThat(created.get("estimatedCost").get("llmCalls").asInt()).isEqualTo(questions * 2 * 5);
        drainQueue();
        JsonNode done = experiment(a, created.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("actualCost").get("tokensIn").asLong()).isPositive();

        // the judge: same model as the generator, never calibrated, with a warning that says so
        JsonNode judge = done.get("judge");
        assertThat(judge.get("model").asText()).isEqualTo("fake-llm");
        assertThat(judge.get("rubricVersion").asText()).isEqualTo(Judge.RUBRIC_VERSION);
        assertThat(judge.get("sameModelAsGenerator").asBoolean()).isTrue();
        assertThat(judge.get("warnings").toString()).contains("also judges");
        for (String m : new String[] {"faithfulness", "correctness", "relevance"}) assertThat(judge.get("metrics").get(m).get("status").asText()).isEqualTo("unknown");

        for (JsonNode run : done.get("runs")) {
            assertThat(run.get("status").asText()).isEqualTo("DONE");
            assertThat(metricOrNull(run, "context_pass", "all")).as("retrieval metrics are still reported").isNotNull();
            assertThat(metricOrNull(run, "false_abstention", "all").get("n").asInt()).isEqualTo(questions);
            assertThat(metricOrNull(run, "correctness", "all").get("n").asInt()).isEqualTo(questions);
            assertThat(metricOrNull(run, "relevance", "all").get("value").asDouble()).isBetween(0.0, 1.0);
            JsonNode faithfulness = metricOrNull(run, "faithfulness", "all");
            assertThat(faithfulness).isNotNull();
            assertThat(faithfulness.get("ciLow").asDouble()).isLessThanOrEqualTo(faithfulness.get("value").asDouble());
        }
        assertThat(done.get("vsBaseline").get(0).get("rows").size()).isGreaterThan(7);

        // every answer, its claims with verdicts, the judge's reasons and a trace X-Ray can open
        String runId = done.get("runs").get(0).get("id").asText();
        JsonNode results = ok(send(get("/api/experiments/" + done.get("id").asText() + "/results?run=" + runId), a, null));
        assertThat(results).hasSize(questions);
        for (JsonNode r : results) {
            assertThat(r.get("answer").asText()).isNotBlank();
            assertThat(r.get("claims").get("claims").isArray()).isTrue();
            assertThat(r.get("claims").get("judge").get("correctness").get("verdict").asText()).isIn("correct", "partial", "wrong");
            JsonNode trace = ok(send(get("/api/traces/" + r.get("traceId").asText()), a, null));
            assertThat(trace.get("kind").asText()).isEqualTo("EXPERIMENT");
            assertThat(trace.get("question").asText()).isEqualTo(r.get("question").asText());
            assertThat(trace.get("data").get("prompt")).isNotEmpty();
        }
        for (JsonNode claim : results.get(0).get("claims").get("claims")) {
            assertThat(claim.get("verdict").asText()).isIn("SUPPORTED", "PARTIAL", "UNSUPPORTED");
            assertThat(claim.get("source").asText()).isIn("NLI", "JUDGE", "NO_CONTEXT");
        }

        // what was used is stored with the run
        int withMeta = count(a, "SELECT count(*) FROM runs WHERE meta->>'judgeModel' = 'fake-llm' AND meta->>'rubricVersion' = 'v1' "
                + "AND meta->>'answerPromptVersion' = 'answer-v1' AND meta->>'seed' = '42' AND (meta->>'temperature')::float = 0");
        assertThat(withMeta).isEqualTo(2);

        // runs compare on the questions both answered, and the questions that flipped are those whose answer stopped being fully correct
        JsonNode cmp = ok(send(get("/api/experiments/compare?a=" + runId + "&b=" + done.get("runs").get(1).get("id").asText()), a, null));
        assertThat(cmp.get("flipMetric").asText()).isEqualTo("correctness");
        assertThat(cmp.get("rows").size()).isGreaterThan(7);
    }

    @Test
    void abstentionIsScoredOnBothSidesAndAnAbstainedAnswerHasNoFaithfulness() throws Exception {
        answerFromContext();
        Account a = accountWithDocs(2, 6);
        String set = finalWithManual(a, "abstain", 3, true, true);
        JsonNode created = createFull(a, set, "abstention", "[{\"name\":\"hybrid\"}]");
        drainQueue();
        JsonNode done = experiment(a, created.get("id").asText());
        JsonNode run = done.get("runs").get(0);
        int answerable = created.get("questionCount").asInt() - 1;

        JsonNode accuracy = metricOrNull(run, "abstention_accuracy", "all");
        assertThat(accuracy.get("n").asInt()).as("one unanswerable question").isEqualTo(1);
        assertThat(accuracy.get("value").asDouble()).as("and the system said it did not know").isEqualTo(1.0);
        JsonNode falseAbstention = metricOrNull(run, "false_abstention", "all");
        assertThat(falseAbstention.get("n").asInt()).isEqualTo(answerable);
        assertThat(falseAbstention.get("value").asDouble()).as("one answerable question was wrongly refused").isCloseTo(1.0 / answerable, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metricOrNull(run, "context_pass", "all").get("n").asInt()).as("no retrieval metric for a question without evidence").isEqualTo(answerable);

        JsonNode results = ok(send(get("/api/experiments/" + done.get("id").asText() + "/results?run=" + run.get("id").asText()), a, null));
        int abstained = 0;
        for (JsonNode r : results) {
            if (!r.get("answer").asText().contains("not in the documents")) continue;
            abstained++;
            assertThat(r.get("metrics").has("faithfulness")).as("nothing was claimed").isFalse();
            assertThat(r.get("claims").get("claims")).isEmpty();
            if (r.get("qtype").asText().equals("UNANSWERABLE")) {
                assertThat(r.get("metrics").get("abstention_accuracy").asDouble()).isEqualTo(1.0);
                assertThat(r.get("metrics").get("correctness").asDouble()).as("saying so is the right answer").isEqualTo(1.0);
                assertThat(r.get("metrics").has("context_pass")).isFalse();
            } else {
                assertThat(r.get("metrics").get("false_abstention").asDouble()).isEqualTo(1.0);
                assertThat(r.get("metrics").get("correctness").asDouble()).as("refusing an answerable question is wrong").isZero();
                assertThat(r.get("metrics").get("relevance").asDouble()).isZero();
            }
        }
        assertThat(abstained).isEqualTo(2);
        assertThat(FakeLlmProvider.JUDGE_CALLS.get()).as("no judge call is spent on an abstention").isEqualTo((answerable - 1) * 2);
    }

    @Test
    void anUnreadableJudgeReplyLeavesTheMetricOutInsteadOfGuessing() throws Exception {
        answerFromContext();
        Account a = accountWithDocs(1, 6);
        String set = finalTestSet(a, "judge", 3);
        FakeLlmProvider.JUDGE_SCRIPT = r -> r.messages().get(0).content().contains("You grade answers") ? "no idea what to say" : null;
        JsonNode created = createFull(a, set, "unreadable", "[{\"name\":\"hybrid\"}]");
        drainQueue();
        JsonNode done = experiment(a, created.get("id").asText());
        JsonNode run = done.get("runs").get(0);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(metricOrNull(run, "correctness", "all")).as("never graded, so no number").isNull();
        assertThat(metricOrNull(run, "relevance", "all")).isNotNull();
        assertThat(metricOrNull(run, "faithfulness", "all")).isNotNull();
        for (JsonNode r : ok(send(get("/api/experiments/" + done.get("id").asText() + "/results?run=" + run.get("id").asText()), a, null))) {
            assertThat(r.get("metrics").has("correctness")).isFalse();
            assertThat(r.get("claims").get("judge").get("correctness").get("error").asText()).contains("could not be read");
        }
    }

    @Test
    void aFullRunThatLosesTheModelResumesWithoutAnsweringAQuestionTwice() throws Exception {
        answerFromContext();
        Account a = accountWithDocs(1, 6);
        String set = finalTestSet(a, "outage", 4);
        JsonNode created = createFull(a, set, "outage", "[{\"name\":\"hybrid\"}]");
        int questions = created.get("questionCount").asInt();
        FakeLlmProvider.reset();
        FakeLlmProvider.FAIL_ON_CALL.set(FakeLlmProvider.CALLS.get() + 7); // part-way through the second question
        drainQueue();
        JsonNode partial = experiment(a, created.get("id").asText());
        assertThat(partial.get("status").asText()).isEqualTo("RUNNING");
        int saved = partial.get("runs").get(0).get("samplesDone").asInt();
        assertThat(saved).isBetween(1, questions - 1);

        tx.worker(j -> j.update("UPDATE jobs SET run_after = now() WHERE status = 'QUEUED'"));
        drainQueue();
        JsonNode done = experiment(a, created.get("id").asText());
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        String run = done.get("runs").get(0).get("id").asText();
        assertThat(count(a, "SELECT count(*) FROM run_results WHERE run_id = '" + run + "'")).isEqualTo(questions);
        assertThat(count(a, "SELECT count(DISTINCT question_id) FROM run_results WHERE run_id = '" + run + "'")).isEqualTo(questions);
        assertThat(done.get("runs").get(0).get("samplesDone").asInt()).isEqualTo(questions);
    }

    @Test
    void viewersCanReadFullResultsButNotStartThem() throws Exception {
        Account owner = accountWithDocs(1, 6);
        String set = finalTestSet(owner, "roles", 3);
        JsonNode created = createFull(owner, set, "roles", "[{\"name\":\"hybrid\"}]");
        drainQueue();
        Account viewer = register();
        mvc.perform(post("/api/workspaces/" + owner.workspace() + "/members").header("Authorization", "Bearer " + owner.token())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        Account asViewer = new Account(viewer.token(), owner.workspace(), viewer.email());
        assertThat(experiment(asViewer, created.get("id").asText()).get("judge").get("model").asText()).isEqualTo("fake-llm");
        send(post("/api/experiments"), asViewer, "{\"testsetId\":\"" + set + "\",\"name\":\"x\",\"mode\":\"FULL\",\"configs\":[{\"name\":\"a\"}]}").andExpect(status().isForbidden());
        Account stranger = register();
        send(get("/api/experiments/" + created.get("id").asText()), stranger, null).andExpect(status().isNotFound());
        send(post("/api/experiments"), stranger, "{\"testsetId\":\"" + UUID.randomUUID() + "\",\"name\":\"x\",\"mode\":\"FULL\",\"configs\":[{\"name\":\"a\"}]}").andExpect(status().isNotFound());
    }
}
