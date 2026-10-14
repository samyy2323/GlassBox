package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.FakeEmbeddingProvider;
import dev.glassbox.ingest.ApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Experiments through the real API, queue and database: runs, metrics, statistics, resume, cancel, stale evidence, roles, isolation. */
class ExperimentApiTest extends EvalApiSupport {
    // ------------------------------------------------------------------ end to end

    @Test
    void runsAThreeConfigurationExperimentAndReportsMetricsWithIntervalsAndVerdicts() throws Exception {
        Account a = accountWithDocs(3, 6);
        String set = finalTestSet(a, "measure", 8);
        JsonNode created = createExperiment(a, set, "vector vs keyword vs hybrid", THREE);
        String id = created.get("id").asText();
        assertThat(created.get("status").asText()).isEqualTo("QUEUED");
        assertThat(created.get("runs")).hasSize(3);
        int questions = created.get("questionCount").asInt();
        assertThat(questions).isBetween(1, 8);

        drainQueue();
        JsonNode done = experiment(a, id);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("finishedAt")).isNotNull();
        assertThat(done.get("actualCost").get("retrievalCalls").asInt()).isEqualTo(questions * 3);
        for (JsonNode run : done.get("runs")) {
            assertThat(run.get("status").asText()).isEqualTo("DONE");
            assertThat(run.get("samplesDone").asInt()).isEqualTo(questions);
            JsonNode pass = metric(run, "context_pass", "all", "question");
            assertThat(pass.get("n").asInt()).isEqualTo(questions);
            assertThat(pass.get("value").asDouble()).isBetween(0.0, 1.0);
            assertThat(pass.get("ciLow").asDouble()).isLessThanOrEqualTo(pass.get("value").asDouble());
            assertThat(pass.get("ciHigh").asDouble()).isGreaterThanOrEqualTo(pass.get("value").asDouble());
            assertThat(metric(run, "context_pass", "all", "cluster").get("n").asInt()).isEqualTo(questions);
            assertThat(metric(run, "latency_p50_ms", "all", "question").get("value").asDouble()).isGreaterThanOrEqualTo(0.0);
            assertThat(metric(run, "mrr", "FACTUAL", "question").get("n").asInt()).as("sliced by question type").isLessThanOrEqualTo(questions);
        }
        // the corpus is built so that a question about a section is answered by that section: hybrid finds it
        JsonNode hybrid = done.get("runs").get(2);
        assertThat(metric(hybrid, "recall@5", "all", "question").get("value").asDouble()).isGreaterThanOrEqualTo(0.75);

        // each non-baseline run is compared with the baseline, seven metrics each, with a verdict sentence
        assertThat(done.get("vsBaseline")).hasSize(2);
        for (JsonNode c : done.get("vsBaseline")) {
            assertThat(c.get("rows")).hasSize(7);
            for (JsonNode row : c.get("rows")) {
                assertThat(row.get("verdict").asText()).matches("(B beats A|A beats B) by .*|No significant difference .*");
                assertThat(row.get("pAdjusted").asDouble()).isBetween(0.0, 1.0);
                assertThat(row.get("test").asText()).isIn("mcnemar", "permutation");
            }
        }
        assertThat(count(a, "SELECT count(*) FROM comparisons")).isEqualTo(2 * 7);

        // per-question results, with what was retrieved and which question failed at which stage
        String runId = done.get("runs").get(0).get("id").asText();
        JsonNode results = ok(send(get("/api/experiments/" + id + "/results?run=" + runId), a, null));
        assertThat(results).hasSize(questions);
        for (JsonNode r : results) {
            assertThat(r.get("metrics").size()).isEqualTo(7);
            assertThat(r.get("retrieved").isArray()).isTrue();
            assertThat(r.get("question").asText()).isNotBlank();
        }

        // and two runs can be compared directly, with the questions that flipped
        JsonNode cmp = ok(send(get("/api/experiments/compare?a=" + runId + "&b=" + done.get("runs").get(2).get("id").asText()), a, null));
        assertThat(cmp.get("rows")).hasSize(7);
        assertThat(cmp.get("questions").asInt()).isEqualTo(questions);
        assertThat(cmp.has("passToFail")).isTrue();
        assertThat(cmp.has("failToPass")).isTrue();
        assertThat(ok(send(get("/api/experiments"), a, null))).hasSize(1);
    }

    @Test
    void theEstimateSaysHowManyQuestionsAndCallsWithoutStartingAnything() throws Exception {
        Account a = accountWithDocs(2, 5);
        String set = finalTestSet(a, "estimate", 4);
        JsonNode e = ok(send(post("/api/experiments/estimate"), a, "{\"testsetId\":\"" + set + "\",\"configs\":" + THREE + "}"));
        assertThat(e.get("runs").asInt()).isEqualTo(3);
        assertThat(e.get("retrievalCalls").asInt()).isEqualTo(e.get("questions").asInt() * 3);
        assertThat(e.get("llmCalls").asInt()).isZero();
        assertThat(e.get("estimatedUsd").asDouble()).isZero();
        assertThat(ok(send(get("/api/experiments"), a, null))).isEmpty();
        assertThat(countJobs("WHERE type = 'RUN_EXPERIMENT'")).isZero();
    }

    @Test
    void aRunThatLosesTheEmbeddingServiceResumesWithoutDuplicates() throws Exception {
        Account a = accountWithDocs(2, 6);
        String set = finalTestSet(a, "resume", 6);
        String id = createExperiment(a, set, "outage", "[{\"name\":\"vector\",\"mode\":\"VECTOR\"},{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]").get("id").asText();
        FakeEmbeddingProvider.FAIL_ON_CALL.set(FakeEmbeddingProvider.CALLS.get() + 3); // the third query of the first run fails
        drainQueue();
        JsonNode partial = experiment(a, id);
        assertThat(partial.get("status").asText()).as("the job failed and will retry").isEqualTo("RUNNING");
        int questions = partial.get("questionCount").asInt();
        assertThat(partial.get("runs").get(0).get("samplesDone").asInt()).isEqualTo(2);

        tx.worker(j -> j.update("UPDATE jobs SET run_after = now() WHERE status = 'QUEUED'"));
        drainQueue();
        JsonNode done = experiment(a, id);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        for (JsonNode run : done.get("runs")) {
            assertThat(run.get("samplesDone").asInt()).isEqualTo(questions);
            assertThat(count(a, "SELECT count(*) FROM run_results WHERE run_id = '" + run.get("id").asText() + "'")).isEqualTo(questions);
            assertThat(count(a, "SELECT count(DISTINCT question_id) FROM run_results WHERE run_id = '" + run.get("id").asText() + "'")).isEqualTo(questions);
        }
        assertThat(count(a, "SELECT count(*) FROM run_metrics WHERE metric = 'context_pass' AND slice = 'all' AND method = 'question'")).as("one aggregate per run").isEqualTo(2);
    }

    @Test
    void aRepeatedDeliveryOfTheSameJobCannotCountAQuestionTwice() throws Exception {
        Account a = accountWithDocs(1, 6);
        String set = finalTestSet(a, "twice", 4);
        String id = createExperiment(a, set, "twice", "[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]").get("id").asText();
        drainQueue();
        int questions = experiment(a, id).get("questionCount").asInt();
        // the same job arrives again after the experiment finished
        tx.worker(j -> j.update("UPDATE jobs SET status = 'QUEUED', run_after = now()"));
        drainQueue();
        assertThat(count(a, "SELECT count(*) FROM run_results")).isEqualTo(questions);
        assertThat(experiment(a, id).get("status").asText()).isEqualTo("DONE");
    }

    @Test
    void questionsWhoseDocumentChangedAreCountedAsStaleAndLeftOut() throws Exception {
        Account a = register();
        String first = uploadOk(a, "First.md", markdown("First", 6, 0).getBytes(StandardCharsets.UTF_8)).get("id").asText();
        uploadOk(a, "Second.md", markdown("Second", 6, 3).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        String set = finalTestSet(a, "stale", 8);
        JsonNode before = ok(send(post("/api/experiments/estimate"), a, "{\"testsetId\":\"" + set + "\",\"configs\":[{\"name\":\"hybrid\"}]}"));
        assertThat(before.get("staleQuestions").asInt()).isZero();

        // replace the first document with a new version: its old character ranges mean nothing now
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT, "/api/documents/" + first)
                        .file(new MockMultipartFile("file", "First.md", null, markdown("First", 6, 9).getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()))
                .andExpect(status().isOk());
        drainQueue();
        JsonNode after = ok(send(post("/api/experiments/estimate"), a, "{\"testsetId\":\"" + set + "\",\"configs\":[{\"name\":\"hybrid\"}]}"));
        assertThat(after.get("staleQuestions").asInt()).isPositive();
        assertThat(after.get("questions").asInt() + after.get("staleQuestions").asInt()).isEqualTo(before.get("questions").asInt());
        assertThat(after.get("notes").toString()).contains("document changed");

        JsonNode created = createExperiment(a, set, "after the change", "[{\"name\":\"hybrid\"}]");
        assertThat(created.get("staleCount").asInt()).isEqualTo(after.get("staleQuestions").asInt());
        drainQueue();
        assertThat(experiment(a, created.get("id").asText()).get("runs").get(0).get("samplesDone").asInt()).isEqualTo(after.get("questions").asInt());
    }

    @Test
    void aCancelledExperimentStopsAndKeepsNoResults() throws Exception {
        Account a = accountWithDocs(1, 6);
        String set = finalTestSet(a, "cancel", 4);
        String id = createExperiment(a, set, "to cancel", "[{\"name\":\"hybrid\"}]").get("id").asText();
        send(post("/api/experiments/" + id + "/cancel"), a, null).andExpect(status().isOk());
        drainQueue();
        JsonNode e = experiment(a, id);
        assertThat(e.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(e.get("runs").get(0).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(count(a, "SELECT count(*) FROM run_results")).isZero();
        send(post("/api/experiments/" + id + "/cancel"), a, null).andExpect(status().isConflict());

        String finished = createExperiment(a, set, "finished", "[{\"name\":\"hybrid\"}]").get("id").asText();
        drainQueue();
        send(post("/api/experiments/" + finished + "/cancel"), a, null).andExpect(status().isConflict());
    }

    // ------------------------------------------------------------------ validation, roles, isolation

    @Test
    void onlyAFinalTestSetWithValidConfigurationsCanBeMeasured() throws Exception {
        Account a = accountWithDocs(1, 5);
        String draft = ok(send(post("/api/testsets/generate"), a, "{\"name\":\"draft\",\"count\":2,\"mix\":{\"FACTUAL\":1}}")).get("testset").get("id").asText();
        drainQueue();
        send(post("/api/experiments"), a, "{\"testsetId\":\"" + draft + "\",\"name\":\"x\",\"configs\":[{\"name\":\"a\"}]}").andExpect(status().isConflict());

        String set = finalTestSet(a, "valid", 2);
        String base = "{\"testsetId\":\"" + set + "\",\"name\":\"x\",\"configs\":";
        send(post("/api/experiments"), a, base + "[]}").andExpect(status().isBadRequest());
        send(post("/api/experiments"), a, base + "[{\"name\":\"a\"},{\"name\":\"a\"}]}").andExpect(status().isBadRequest());
        send(post("/api/experiments"), a, base + "[{\"name\":\"\"}]}").andExpect(status().isBadRequest());
        send(post("/api/experiments"), a, base + "[{\"name\":\"a\",\"kFused\":0}]}").andExpect(status().isBadRequest());
        send(post("/api/experiments"), a, base + "[{\"name\":\"a\",\"mode\":\"TELEPATHY\"}]}").andExpect(status().isBadRequest());
        send(post("/api/experiments"), a, base + "[{\"name\":\"a\",\"wVec\":0,\"wKw\":0}]}").andExpect(status().isBadRequest());
        send(post("/api/experiments"), a, base + "[{\"name\":\"a\",\"topN\":500}]}").andExpect(status().isBadRequest());
        StringBuilder nine = new StringBuilder("[");
        for (int i = 0; i < 9; i++) nine.append(i > 0 ? "," : "").append("{\"name\":\"c").append(i).append("\"}");
        send(post("/api/experiments"), a, base + nine + "]}").andExpect(status().isBadRequest());
        send(post("/api/experiments"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"x\",\"mode\":\"NONSENSE\",\"configs\":[{\"name\":\"a\"}]}").andExpect(status().isBadRequest());
        send(post("/api/experiments"), a, "{\"testsetId\":\"" + UUID.randomUUID() + "\",\"name\":\"x\",\"configs\":[{\"name\":\"a\"}]}").andExpect(status().isNotFound());
        assertThat(ok(send(get("/api/experiments"), a, null))).isEmpty();
    }

    @Test
    void runsOfDifferentTestSetsCannotBeCompared() throws Exception {
        Account a = accountWithDocs(2, 6);
        String one = finalTestSet(a, "one", 4), two = finalTestSet(a, "two", 4);
        JsonNode e1 = createExperiment(a, one, "e1", "[{\"name\":\"x\"}]"), e2 = createExperiment(a, two, "e2", "[{\"name\":\"y\"}]");
        drainQueue();
        String r1 = experiment(a, e1.get("id").asText()).get("runs").get(0).get("id").asText(), r2 = experiment(a, e2.get("id").asText()).get("runs").get(0).get("id").asText();
        send(get("/api/experiments/compare?a=" + r1 + "&b=" + r2), a, null).andExpect(status().isConflict());
        send(get("/api/experiments/compare?a=" + r1 + "&b=" + UUID.randomUUID()), a, null).andExpect(status().isNotFound());
    }

    @Test
    void viewersReadButCannotRunAndOtherWorkspacesSeeNothing() throws Exception {
        Account owner = accountWithDocs(1, 6);
        String set = finalTestSet(owner, "private", 4);
        String id = createExperiment(owner, set, "private run", "[{\"name\":\"hybrid\"}]").get("id").asText();
        drainQueue();
        String run = experiment(owner, id).get("runs").get(0).get("id").asText();

        Account viewer = register();
        mvc.perform(post("/api/workspaces/" + owner.workspace() + "/members").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        Account asViewer = new Account(viewer.token(), owner.workspace(), viewer.email());
        assertThat(experiment(asViewer, id).get("status").asText()).isEqualTo("DONE");
        ok(send(get("/api/experiments"), asViewer, null));
        ok(send(get("/api/experiments/" + id + "/results?run=" + run), asViewer, null));
        ok(send(post("/api/experiments/estimate"), asViewer, "{\"testsetId\":\"" + set + "\",\"configs\":[{\"name\":\"a\"}]}"));
        send(post("/api/experiments"), asViewer, "{\"testsetId\":\"" + set + "\",\"name\":\"x\",\"configs\":[{\"name\":\"a\"}]}").andExpect(status().isForbidden());
        send(post("/api/experiments/" + id + "/cancel"), asViewer, null).andExpect(status().isForbidden());

        Account stranger = register();
        send(get("/api/experiments/" + id), stranger, null).andExpect(status().isNotFound());
        send(get("/api/experiments/" + id + "/results?run=" + run), stranger, null).andExpect(status().isNotFound());
        send(post("/api/experiments/" + id + "/cancel"), stranger, null).andExpect(status().isNotFound());
        send(get("/api/experiments/compare?a=" + run + "&b=" + run), stranger, null).andExpect(status().isNotFound());
        assertThat(ok(send(get("/api/experiments"), stranger, null))).isEmpty();
        send(post("/api/experiments"), stranger, "{\"testsetId\":\"" + set + "\",\"name\":\"x\",\"configs\":[{\"name\":\"a\"}]}").andExpect(status().isNotFound());
        mvc.perform(get("/api/experiments")).andExpect(status().isUnauthorized());
    }

    @Test
    void configurationsAreStoredOnceAndReusedAcrossExperiments() throws Exception {
        Account a = accountWithDocs(1, 6);
        String set = finalTestSet(a, "reuse", 3);
        createExperiment(a, set, "first", "[{\"name\":\"hybrid\"},{\"name\":\"vector\",\"mode\":\"VECTOR\"}]");
        createExperiment(a, set, "second", "[{\"name\":\"the same hybrid\"}]");
        assertThat(count(a, "SELECT count(*) FROM pipeline_configs")).as("identical configurations share one row").isEqualTo(2);
        Set<String> labels = new HashSet<>();
        for (JsonNode e : ok(send(get("/api/experiments"), a, null))) e.get("runs").forEach(r -> labels.add(r.get("label").asText()));
        assertThat(labels).containsExactlyInAnyOrder("hybrid", "vector", "the same hybrid");
        List<String> ids = new ArrayList<>();
        ok(send(get("/api/experiments"), a, null)).forEach(e -> ids.add(e.get("id").asText()));
        assertThat(ids).hasSize(2);
    }
}
