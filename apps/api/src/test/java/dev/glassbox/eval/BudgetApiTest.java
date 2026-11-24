package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Prices, budgets, the check before a run, the abort rule during it and the estimate-against-actual record after it. */
class BudgetApiTest extends EvalApiSupport {
    private String full(Account a, String set, String name, String extra) {
        return "{\"testsetId\":\"" + set + "\",\"name\":\"" + name + "\",\"mode\":\"FULL\",\"configs\":[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private void price(Account a, double prompt, double completion) throws Exception {
        ok(send(put("/api/budget/prices"), a, "{\"model\":\"fake-llm\",\"promptUsdPerMillion\":" + prompt + ",\"completionUsdPerMillion\":" + completion + "}"));
    }

    @Test
    void theEstimateHasABreakdownAndAPriceFromTheWorkspacesTable() throws Exception {
        Account a = accountWithDocs(2, 4);
        String set = finalTestSet(a, "price", 6);
        String body = "{\"testsetId\":\"" + set + "\",\"mode\":\"FULL\",\"configs\":[{\"name\":\"a\"}]}";
        JsonNode free = ok(send(post("/api/experiments/estimate"), a, body));
        assertThat(free.get("estimatedUsd").asDouble()).isZero();
        assertThat(free.get("estimatedTokens").asLong()).isPositive();
        assertThat(free.get("breakdown").get("generation").get("calls").asInt()).isEqualTo(free.get("questions").asInt());
        // four judge calls a question, less the share the cache is expected to serve (which depends on what this JVM has already run)
        double miss = 1 - free.get("breakdown").get("cacheHitRate").asDouble();
        assertThat(free.get("breakdown").get("judging").get("calls").asInt()).isEqualTo((int) Math.round(4 * free.get("questions").asInt() * miss));

        price(a, 1000, 2000);
        JsonNode priced = ok(send(post("/api/experiments/estimate"), a, body));
        assertThat(priced.get("estimatedUsd").asDouble()).isGreaterThan(0.0);
        assertThat(priced.get("breakdown").get("usd").asDouble()).isEqualTo(priced.get("estimatedUsd").asDouble());
        assertThat(priced.get("needsConfirmation").asBoolean()).isFalse();
    }

    @Test
    void aRunWhoseEstimateIsOverItsCapOrTheMonthlyBudgetIsRefusedWithTheNumbers() throws Exception {
        Account a = accountWithDocs(2, 4);
        String set = finalTestSet(a, "caps", 6);
        price(a, 1000, 2000);
        String est = ok(send(post("/api/experiments/estimate"), a, full(a, set, "x", "").replace("\"name\":\"x\",", ""))).get("estimatedUsd").asText();
        assertThat(Double.parseDouble(est)).isGreaterThan(0.01);

        String refused = send(post("/api/experiments"), a, full(a, set, "tiny cap", "\"capUsd\":0.001")).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("over its cap of $0.00");

        ok(send(put("/api/budget"), a, "{\"monthlyUsd\":0.5,\"perRunUsd\":null,\"confirmAboveUsd\":null}"));
        String month = send(post("/api/experiments"), a, full(a, set, "over the month", "")).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(month).contains("monthly budget of $0.50");
        assertThat(count(a, "SELECT count(*) FROM experiments")).as("a refused run leaves nothing behind").isZero();

        ok(send(put("/api/budget"), a, "{\"monthlyUsd\":null,\"perRunUsd\":0.001,\"confirmAboveUsd\":null}"));
        send(post("/api/experiments"), a, full(a, set, "workspace cap", "")).andExpect(status().isConflict());
        send(post("/api/experiments"), a, full(a, set, "own cap wins", "\"capUsd\":1000000")).andExpect(status().isAccepted());
    }

    @Test
    void aRunAboveTheConfirmationThresholdStartsOnlyWhenConfirmed() throws Exception {
        Account a = accountWithDocs(2, 4);
        String set = finalTestSet(a, "confirm", 6);
        price(a, 1000, 2000);
        ok(send(put("/api/budget"), a, "{\"monthlyUsd\":null,\"perRunUsd\":null,\"confirmAboveUsd\":0.001}"));
        JsonNode est = ok(send(post("/api/experiments/estimate"), a, "{\"testsetId\":\"" + set + "\",\"mode\":\"FULL\",\"configs\":[{\"name\":\"a\"}]}"));
        assertThat(est.get("needsConfirmation").asBoolean()).isTrue();
        String refused = send(post("/api/experiments"), a, full(a, set, "unconfirmed", "")).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(refused).contains("needs confirmation").contains("Confirm to start");
        send(post("/api/experiments"), a, full(a, set, "confirmed", "\"confirm\":true")).andExpect(status().isAccepted());
    }

    @Test
    void aRunThatPassesOneHundredTwentyPercentOfItsCapIsAbortedAndKeepsWhatItMeasured() throws Exception {
        FullExperimentApiTest.answerFromContext();
        Account a = accountWithDocs(2, 6);
        String set = finalTestSet(a, "abort", 8);
        JsonNode created = ok(send(post("/api/experiments"), a, full(a, set, "to abort", "\"capCalls\":100000")));
        String id = created.get("id").asText();
        // the cap is tightened after the estimate was accepted: five calls a question, so the third question starts at 10 calls against a limit of 6
        tx.workspace(a.workspace(), null, j -> j.update("UPDATE experiments SET cost_cap_calls = 5 WHERE id = ?::uuid", id));
        drainQueue();

        JsonNode done = experiment(a, id);
        assertThat(done.get("status").asText()).isEqualTo("ABORTED");
        assertThat(done.get("error").asText()).contains("Stopped by its budget").contains("120%");
        int kept = count(a, "SELECT count(*) FROM run_results");
        assertThat(kept).as("what was measured is kept, and the run stopped early").isBetween(1, created.get("questionCount").asInt() - 1);
        assertThat(count(a, "SELECT count(*) FROM spend_log WHERE aborted")).isEqualTo(1);
        assertThat(done.get("runs").get(0).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void everyFinishedRunRecordsItsEstimateAgainstItsActualAndTheBudgetPageShowsTheError() throws Exception {
        FullExperimentApiTest.answerFromContext();
        Account a = accountWithDocs(2, 6);
        String set = finalTestSet(a, "record", 6);
        String id = ok(send(post("/api/experiments"), a, full(a, set, "recorded", ""))).get("id").asText();
        drainQueue();
        assertThat(experiment(a, id).get("status").asText()).isEqualTo("DONE");
        assertThat(count(a, "SELECT count(*) FROM spend_log WHERE kind = 'EXPERIMENT' AND NOT aborted")).isEqualTo(1);

        JsonNode overview = ok(send(get("/api/budget"), a, null));
        JsonNode entry = overview.get("recent").get(0);
        assertThat(entry.get("estimatedTokens").asLong()).isPositive();
        assertThat(entry.get("actualTokens").asLong()).isPositive();
        assertThat(overview.get("measuredRuns").asInt()).isEqualTo(1);
        double error = overview.get("tokenError").asDouble();
        assertThat(error).as("(actual - estimated) / estimated; the estimate assumes full-size prompts, the fake model uses small ones").isBetween(-1.0, 1.0);
        assertThat(error).isEqualTo((entry.get("actualTokens").asDouble() - entry.get("estimatedTokens").asDouble()) / entry.get("estimatedTokens").asDouble(),
                org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void onlyOwnersChangeBudgetsAndPricesAndOtherWorkspacesSeeNone() throws Exception {
        Account owner = accountWithDocs(1, 3);
        ok(send(put("/api/budget"), owner, "{\"monthlyUsd\":50,\"perRunUsd\":5,\"confirmAboveUsd\":2}"));
        price(owner, 1, 2);
        Account editor = register();
        mvc.perform(post("/api/workspaces/" + owner.workspace() + "/members").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + editor.email() + "\",\"role\":\"EDITOR\"}")).andExpect(status().isCreated());
        Account asEditor = new Account(editor.token(), owner.workspace(), editor.email());
        assertThat(ok(send(get("/api/budget"), asEditor, null)).get("budget").get("monthlyUsd").asDouble()).isEqualTo(50.0);
        send(put("/api/budget"), asEditor, "{\"monthlyUsd\":1}").andExpect(status().isForbidden());
        send(put("/api/budget/prices"), asEditor, "{\"model\":\"m\",\"promptUsdPerMillion\":1,\"completionUsdPerMillion\":1}").andExpect(status().isForbidden());
        send(delete("/api/budget/prices?model=fake-llm"), asEditor, null).andExpect(status().isForbidden());
        send(put("/api/budget"), owner, "{\"monthlyUsd\":-1}").andExpect(status().isBadRequest());
        send(put("/api/budget/prices"), owner, "{\"model\":\"\",\"promptUsdPerMillion\":1,\"completionUsdPerMillion\":1}").andExpect(status().isBadRequest());

        Account other = register();
        JsonNode theirs = ok(send(get("/api/budget"), other, null));
        assertThat(theirs.get("budget").path("monthlyUsd").isMissingNode() || theirs.get("budget").path("monthlyUsd").isNull()).isTrue();
        assertThat(theirs.get("prices")).isEmpty();
        assertThat(ok(send(delete("/api/budget/prices?model=fake-llm"), owner, null))).isEmpty();
    }
}
