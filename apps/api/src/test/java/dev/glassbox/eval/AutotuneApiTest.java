package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.FakeEmbeddingProvider;
import dev.glassbox.autotune.Splits;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** AutoTune through the real API, queue and database, with the fake embedding and language models. */
class AutotuneApiTest extends EvalApiSupport {
    private static final String SMALL = "\"stage1Candidates\":6,\"stage2Candidates\":6,\"finalists\":2,\"seed\":7";

    private JsonNode create(Account a, String set, String name, String extra) throws Exception {
        return ok(send(post("/api/autotune"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"" + name + "\"," + SMALL + (extra.isEmpty() ? "" : "," + extra) + "}"));
    }

    private JsonNode autotune(Account a, String id) throws Exception {
        return ok(send(get("/api/autotune/" + id), a, null));
    }

    private Account accountAndSet(String setName, int docs, int sections, int questions, String[] setId) throws Exception {
        Account a = accountWithDocs(docs, sections);
        setId[0] = finalTestSet(a, setName, questions);
        return a;
    }

    // ------------------------------------------------------------------ the search

    @Test
    void searchesOnTheTuningSplitConfirmsOnHeldOutAndReportsAVerdictAgainstTheBaseline() throws Exception {
        String[] set = new String[1];
        Account a = accountAndSet("tune", 3, 6, 14, set);
        JsonNode created = create(a, set[0], "first search", "");
        String id = created.get("id").asText();
        assertThat(created.get("status").asText()).isEqualTo("QUEUED");
        assertThat(created.get("split").get("tuning").asInt() + created.get("split").get("heldOut").asInt()).isEqualTo(created.get("split").get("tuningAnswerable").asInt() + created.get("split").get("heldOutAnswerable").asInt());

        drainQueue();
        JsonNode done = autotune(a, id);
        assertThat(done.get("status").asText()).as("error: %s", done.get("error")).isEqualTo("DONE");
        assertThat(done.get("stage").asText()).isEqualTo("DONE");
        assertThat(done.get("candidates").get("STAGE1").asInt()).isEqualTo(6);
        assertThat(done.get("candidates").get("STAGE2").asInt()).isEqualTo(6);

        JsonNode result = done.get("result");
        JsonNode headline = result.get("headline");
        assertThat(headline.get("mode").asText()).isEqualTo("RETRIEVAL");
        assertThat(headline.get("heldOut").get("n").asInt()).isEqualTo(done.get("split").get("heldOutAnswerable").asInt());
        assertThat(headline.get("heldOut").get("ciLow").asDouble()).isLessThanOrEqualTo(headline.get("heldOut").get("quality").asDouble());
        assertThat(headline.get("verdict").asText()).matches("(B beats A|A beats B) by .*|No significant difference .*|The baseline itself .*");
        assertThat(headline.get("qualityDefinition").asText()).contains("context recall");

        // the baseline is always measured and first, finalists follow; each carries its held-out score and its tuning score
        JsonNode rows = result.get("candidates");
        assertThat(rows.get(0).get("role").asText()).isEqualTo("BASELINE");
        assertThat(rows.get(0).get("config").get("chunking").get("strategy").asText()).isEqualTo("fixed");
        assertThat(rows.size()).isBetween(2, 3);
        for (JsonNode r : rows) {
            assertThat(r.get("heldOut").get("quality").asDouble()).isBetween(0.0, 1.0);
            assertThat(r.get("heldOut").get("promptTokens").asDouble()).isGreaterThan(0.0);
            assertThat(r.has("tuning")).isTrue();
            assertThat(r.get("onFrontier").isBoolean()).isTrue();
        }
        assertThat(rows.get(0).has("vsBaseline")).isFalse();
        assertThat(rows.get(1).get("vsBaseline").get("pAdjusted").asDouble()).isBetween(0.0, 1.0);

        // three named recommendations, all of them among the measured candidates
        Set<String> keys = new HashSet<>();
        rows.forEach(r -> keys.add(r.get("key").asText()));
        assertThat(result.get("recommendations").get("bestQuality").asText()).isIn(keys);
        assertThat(result.get("recommendations").get("bestValue").asText()).isIn(keys);
        assertThat(result.get("recommendations").get("fastest").asText()).isIn(keys);
        assertThat(result.get("stages").get("stage1").get("rounds")).isNotEmpty();
        assertThat(result.get("effects").isArray()).isTrue();
        assertThat(done.get("spent").get("retrievalCalls").asInt()).isGreaterThan(0);
    }

    @Test
    void noChoiceIsEverMadeOnHeldOutQuestions() throws Exception {
        String[] set = new String[1];
        Account a = accountAndSet("leak", 3, 6, 14, set);
        String id = create(a, set[0], "no leak", "").get("id").asText();
        drainQueue();
        JsonNode done = autotune(a, id);
        assertThat(done.get("status").asText()).isEqualTo("DONE");

        List<Splits.Item> items = new java.util.ArrayList<>();
        for (JsonNode q : ok(send(get("/api/testsets/" + set[0] + "/questions?limit=200"), a, null)).get("items")) items.add(new Splits.Item(UUID.fromString(q.get("id").asText()), q.get("qtype").asText()));
        Splits.Result split = Splits.split(7, items);
        assertThat(split.heldOut()).isNotEmpty();

        // stages 1 and 2 stored scores only for tuning questions
        Set<String> scored = new HashSet<>();
        tx.workspace(a.workspace(), null, j -> {
            j.query("SELECT jsonb_object_keys(scores) AS k FROM autotune_candidates WHERE autotune_id = ?::uuid", rs -> { scored.add(rs.getString("k")); }, id);
            return null;
        });
        assertThat(scored).isNotEmpty();
        for (String q : scored) assertThat(split.isHeldOut(UUID.fromString(q))).as("question %s scored while tuning", q).isFalse();

        // the held-out runs contain only held-out questions, every one of them
        String experiment = done.get("experimentId").asText();
        Set<String> heldRun = new HashSet<>();
        tx.workspace(a.workspace(), null, j -> {
            j.query("SELECT DISTINCT r.question_id FROM run_results r JOIN runs u ON u.id = r.run_id WHERE u.experiment_id = ?::uuid AND u.label LIKE '%held-out'", rs -> { heldRun.add(rs.getString(1)); }, experiment);
            return null;
        });
        assertThat(heldRun).isNotEmpty();
        for (String q : heldRun) assertThat(split.isHeldOut(UUID.fromString(q))).isTrue();
        assertThat(count(a, "SELECT count(*) FROM runs WHERE experiment_id = '" + experiment + "' AND label LIKE '%tuning'")).as("retrieval-only: no tuning runs").isZero();
    }

    @Test
    void theFullStageMeasuresFinalistsWithAnswersAndReportsTheGapBetweenTuningAndHeldOut() throws Exception {
        FullExperimentApiTest.answerFromContext();
        String[] set = new String[1];
        Account a = accountAndSet("full", 2, 6, 12, set);
        String id = create(a, set[0], "full search", "\"fullStage\":true,\"fullQuestions\":4").get("id").asText();
        drainQueue();
        JsonNode done = autotune(a, id);
        assertThat(done.get("status").asText()).as("error: %s", done.get("error")).isEqualTo("DONE");
        JsonNode headline = done.get("result").get("headline");
        assertThat(headline.get("mode").asText()).isEqualTo("FULL");
        assertThat(headline.get("qualityDefinition").asText()).contains("50% context recall");
        assertThat(headline.get("heldOut").has("correctness")).isTrue();
        assertThat(headline.get("heldOut").has("faithfulness")).isTrue();
        assertThat(headline.get("tuning").get("n").asInt()).isEqualTo(4);
        assertThat(done.get("spent").get("llmCalls").asInt()).isGreaterThan(0);
        // the internal experiment keeps the finalists' runs, with traces, so their answers open in X-Ray
        String experiment = done.get("experimentId").asText();
        assertThat(count(a, "SELECT count(*) FROM runs WHERE experiment_id = '" + experiment + "' AND label LIKE '%tuning'")).isGreaterThanOrEqualTo(2);
        assertThat(count(a, "SELECT count(*) FROM run_results r JOIN runs u ON u.id = r.run_id WHERE u.experiment_id = '" + experiment + "' AND r.trace_id IS NOT NULL")).isGreaterThan(0);
        // and they are not listed among the user's own experiments
        assertThat(ok(send(get("/api/experiments"), a, null))).isEmpty();
        assertThat(ok(send(get("/api/experiments/" + experiment), a, null)).get("kind").asText()).isEqualTo("AUTOTUNE");
    }

    @Test
    void theSpendCapStopsTheSearchAndKeepsTheBestSoFar() throws Exception {
        String[] set = new String[1];
        Account a = accountAndSet("cap", 3, 6, 14, set);
        String id = create(a, set[0], "capped", "\"capSearches\":12").get("id").asText();
        drainQueue();
        JsonNode done = autotune(a, id);
        assertThat(done.get("status").asText()).as("error: %s", done.get("error")).isEqualTo("DONE");
        assertThat(done.get("stoppedBy").asText()).isEqualTo("SPEND_CAP");
        assertThat(done.get("result").get("stoppedBy").asText()).isEqualTo("SPEND_CAP");
        assertThat(done.get("result").get("candidates")).isNotEmpty();
        assertThat(done.get("result").get("headline").get("heldOut").get("n").asInt()).as("the held-out check still runs").isGreaterThan(0);
        // the cap counts searches in stages 1 and 2; the held-out check afterwards is not part of it
        assertThat(done.get("spent").get("retrievalCalls").asInt()).isGreaterThanOrEqualTo(12);
    }

    @Test
    void aSearchThatLosesTheEmbeddingServiceResumesWithoutRepeatingScoredQuestions() throws Exception {
        String[] set = new String[1];
        Account a = accountAndSet("resume", 3, 6, 14, set);
        String id = create(a, set[0], "outage", "").get("id").asText();
        FakeEmbeddingProvider.FAIL_ON_CALL.set(FakeEmbeddingProvider.CALLS.get() + 60);
        drainQueue();
        assertThat(autotune(a, id).get("status").asText()).as("the job failed and will retry").isEqualTo("RUNNING");
        int stored = count(a, "SELECT coalesce(sum((SELECT count(*) FROM jsonb_object_keys(scores))), 0)::int FROM autotune_candidates");
        assertThat(stored).isGreaterThan(0);
        int callsBefore = FakeEmbeddingProvider.CALLS.get();

        tx.worker(j -> j.update("UPDATE jobs SET run_after = now() WHERE status = 'QUEUED'"));
        drainQueue();
        JsonNode done = autotune(a, id);
        assertThat(done.get("status").asText()).as("error: %s", done.get("error")).isEqualTo("DONE");
        assertThat(callsBefore).isGreaterThan(0);
        // no (run, question) twice, no candidate row twice
        assertThat(count(a, "SELECT count(*) FROM (SELECT run_id, question_id FROM run_results GROUP BY 1, 2 HAVING count(*) > 1) d")).isZero();
        assertThat(count(a, "SELECT count(*) FROM (SELECT stage, key FROM autotune_candidates GROUP BY 1, 2 HAVING count(*) > 1) d")).isZero();
    }

    // ------------------------------------------------------------------ promote, validation, roles

    @Test
    void promotingTheWinnerMakesItTheWorkspaceDefaultForSearchAndForNewUploads() throws Exception {
        String[] set = new String[1];
        Account a = accountAndSet("promote", 3, 6, 14, set);
        String id = create(a, set[0], "to promote", "").get("id").asText();
        drainQueue();
        JsonNode winner = autotune(a, id).get("result").get("headline").get("winnerConfig");

        JsonNode before = ok(send(post("/api/search"), a, "{\"query\":\"operators health report\"}"));
        JsonNode promoted = ok(send(post("/api/autotune/" + id + "/promote"), a, null));
        assertThat(promoted).isEqualTo(winner);
        assertThat(count(a, "SELECT count(*) FROM workspace_settings WHERE default_pipeline IS NOT NULL")).isEqualTo(1);

        JsonNode after = ok(send(post("/api/search"), a, "{\"query\":\"operators health report\"}"));
        assertThat(after.get("config").get("topN")).isEqualTo(winner.get("topN"));
        assertThat(after.get("config").get("tokenBudget")).isEqualTo(winner.get("tokenBudget"));
        assertThat(after.get("config").get("retrieval").get("mode")).isEqualTo(winner.get("retrieval").get("mode"));
        assertThat(before.get("config").get("topN").asInt()).isEqualTo(5);

        // a document uploaded afterwards is also indexed the way the winner searches, before it counts as ready
        String doc = uploadOk(a, "Later.md", markdown("Later", 3, 5).getBytes(java.nio.charset.StandardCharsets.UTF_8)).get("id").asText();
        drainQueue();
        int chunkings = count(a, "SELECT count(DISTINCT chunking_id) FROM chunks WHERE document_id = '" + doc + "'");
        assertThat(chunkings).isEqualTo(winner.has("chunking") ? 2 : 1);
        String model = winner.get("retrieval").get("model").asText();
        assertThat(count(a, "SELECT count(*) FROM embeddings e JOIN chunks c ON c.id = e.chunk_id WHERE c.document_id = '" + doc + "' AND e.model = '" + model + "'")).isGreaterThan(0);
    }

    @Test
    void unfinishedAndForeignRunsCannotBePromotedAndOnlyOwnersPromote() throws Exception {
        String[] set = new String[1];
        Account owner = accountAndSet("owners", 3, 6, 14, set);
        String id = create(owner, set[0], "x", "").get("id").asText();
        send(post("/api/autotune/" + id + "/promote"), owner, null).andExpect(status().isConflict());
        drainQueue();

        Account editor = register();
        mvc.perform(post("/api/workspaces/" + owner.workspace() + "/members").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + editor.email() + "\",\"role\":\"EDITOR\"}")).andExpect(status().isCreated());
        Account asEditor = new Account(editor.token(), owner.workspace(), editor.email());
        send(post("/api/autotune/" + id + "/promote"), asEditor, null).andExpect(status().isForbidden());
        ok(send(post("/api/autotune/" + id + "/promote"), owner, null));

        Account stranger = register();
        send(get("/api/autotune/" + id), stranger, null).andExpect(status().isNotFound());
        send(post("/api/autotune/" + id + "/promote"), stranger, null).andExpect(status().isNotFound());
    }

    @Test
    void rejectsBadInputAndAnUnfinalizedTestSet() throws Exception {
        String[] set = new String[1];
        Account a = accountAndSet("bad", 2, 4, 8, set);
        for (String bad : List.of("\"objective\":\"WORLD_PEACE\"", "\"finalists\":0", "\"finalists\":9", "\"stage1Candidates\":0", "\"qualityFloor\":2", "\"capMinutes\":0", "\"capSearches\":0")) {
            send(post("/api/autotune"), a, "{\"testsetId\":\"" + set[0] + "\",\"name\":\"bad\"," + bad + "}").andExpect(status().isBadRequest());
        }
        send(post("/api/autotune"), a, "{\"testsetId\":\"" + set[0] + "\",\"name\":\"\"}").andExpect(status().isBadRequest());
        String draft = ok(send(post("/api/testsets/generate"), a, "{\"name\":\"draft\",\"count\":3,\"seed\":3,\"mix\":{\"FACTUAL\":1}}")).get("testset").get("id").asText();
        drainQueue();
        send(post("/api/autotune"), a, "{\"testsetId\":\"" + draft + "\",\"name\":\"draft\"}").andExpect(status().isConflict());
    }

    @Test
    void viewersReadButCannotStartOrCancel() throws Exception {
        String[] set = new String[1];
        Account owner = accountAndSet("viewers", 2, 6, 10, set);
        String id = create(owner, set[0], "v", "").get("id").asText();
        drainQueue();
        Account viewer = register();
        mvc.perform(post("/api/workspaces/" + owner.workspace() + "/members").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        Account asViewer = new Account(viewer.token(), owner.workspace(), viewer.email());
        assertThat(autotune(asViewer, id).get("status").asText()).isEqualTo("DONE");
        assertThat(ok(send(get("/api/autotune"), asViewer, null))).hasSize(1);
        ok(send(post("/api/autotune/estimate"), asViewer, "{\"testsetId\":\"" + set[0] + "\"}"));
        send(post("/api/autotune"), asViewer, "{\"testsetId\":\"" + set[0] + "\",\"name\":\"x\"}").andExpect(status().isForbidden());
        send(post("/api/autotune/" + id + "/cancel"), asViewer, null).andExpect(status().isForbidden());
    }

    @Test
    void cancellingAQueuedSearchStopsItBeforeAnythingRuns() throws Exception {
        String[] set = new String[1];
        Account a = accountAndSet("cancel", 2, 6, 10, set);
        String id = create(a, set[0], "cancel me", "").get("id").asText();
        send(post("/api/autotune/" + id + "/cancel"), a, null).andExpect(status().isOk());
        drainQueue();
        JsonNode c = autotune(a, id);
        assertThat(c.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(count(a, "SELECT count(*) FROM autotune_candidates")).isZero();
        send(post("/api/autotune/" + id + "/cancel"), a, null).andExpect(status().isConflict());
    }

    @Test
    void theEstimateSaysHowTheTestSetIsSplitAndWhatItWillCost() throws Exception {
        String[] set = new String[1];
        Account a = accountAndSet("estimate", 3, 6, 14, set);
        JsonNode e = ok(send(post("/api/autotune/estimate"), a, "{\"testsetId\":\"" + set[0] + "\"," + SMALL + "}"));
        assertThat(e.get("tuningQuestions").asInt()).isGreaterThan(e.get("heldOutQuestions").asInt());
        assertThat(e.get("heldOutQuestions").asInt()).isGreaterThan(0);
        assertThat(e.get("stage1Candidates").asInt()).isEqualTo(6);
        assertThat(e.get("retrievalCalls").asLong()).isGreaterThan(0);
        assertThat(e.get("llmCalls").asLong()).isZero();
        List<String> notes = new java.util.ArrayList<>();
        e.get("notes").forEach(n -> notes.add(n.asText()));
        assertThat(notes).anyMatch(n -> n.contains("held out")).anyMatch(n -> n.contains("stops after"));
        JsonNode full = ok(send(post("/api/autotune/estimate"), a, "{\"testsetId\":\"" + set[0] + "\",\"fullStage\":true,\"fullQuestions\":5," + SMALL + "}"));
        assertThat(full.get("llmCalls").asLong()).isGreaterThan(0);
    }
}
