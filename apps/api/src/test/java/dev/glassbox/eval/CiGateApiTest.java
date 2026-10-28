package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** SPEC 14, the gate API: it passes a good change, blocks a deliberately bad one, and says why in a form a pull request and a CI system can use. */
class CiGateApiTest extends EvalApiSupport {
    private static final String ROOMY = "{\"name\":\"roomy\",\"mode\":\"HYBRID\",\"tokenBudget\":8000}";
    private static final String TINY = "{\"name\":\"tiny\",\"mode\":\"HYBRID\",\"tokenBudget\":100}";

    private record Setup(Account account, String testset, String baselineRun) {}

    /** Bulky documents, so a 100-token budget cannot hold the evidence while an 8000-token one can; a baseline run measured with the roomy configuration. */
    private Setup setup() throws Exception {
        Account a = register();
        for (int i = 0; i < 3; i++) uploadOk(a, "Bulky" + i + ".md", bulkyMarkdown("Bulky" + i, 4, i).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        String set = finalTestSet(a, "gated", 12);
        String base = createExperiment(a, set, "baseline", "[" + ROOMY + "]").get("id").asText();
        drainQueue();
        JsonNode done = experiment(a, base);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        return new Setup(a, set, done.get("runs").get(0).get("id").asText());
    }

    private String gateBody(Setup s, String config, String failUnder, String failOnRegression, String extra) {
        return "{\"testsetId\":\"" + s.testset() + "\",\"name\":\"PR 7\",\"config\":" + config + ",\"baselineRunId\":\"" + s.baselineRun() + "\""
                + (failUnder == null ? "" : ",\"failUnder\":" + failUnder) + (failOnRegression == null ? "" : ",\"failOnRegression\":" + failOnRegression)
                + (extra == null ? "" : "," + extra) + "}";
    }

    private String start(Setup s, String body) throws Exception {
        return ok(send(post("/api/ci/run"), s.account(), body)).get("id").asText();
    }

    private JsonNode result(Setup s, String id) throws Exception {
        return ok(send(get("/api/ci/runs/" + id), s.account(), null));
    }

    @Test
    void aGoodChangePassesAndADeliberatelyBadOneIsBlockedWithTheReason() throws Exception {
        Setup s = setup();

        String good = start(s, gateBody(s, ROOMY, "{\"context_pass\":0.5}", "{\"context_pass\":0.05}", null));
        JsonNode running = result(s, good);
        assertThat(running.get("state").asText()).isEqualTo("RUNNING");
        assertThat(running.has("passed")).as("no verdict yet").isFalse();
        drainQueue();
        JsonNode passed = result(s, good);
        assertThat(passed.get("state").asText()).as("note: %s", passed.get("note")).isEqualTo("PASSED");
        assertThat(passed.get("passed").asBoolean()).isTrue();
        assertThat(passed.get("checks")).hasSize(2).allSatisfy(c -> assertThat(c.get("passed").asBoolean()).isTrue());
        assertThat(experiment(s.account(), good).get("kind").asText()).as("a gate run stays out of the manual experiments").isEqualTo("REGRESSION");

        // a 100-token budget cannot hold the evidence: the change that would ship a regression
        String bad = start(s, gateBody(s, TINY, "{\"context_pass\":0.5}", "{\"context_pass\":0.05}", null));
        drainQueue();
        JsonNode blocked = result(s, bad);
        assertThat(blocked.get("state").asText()).as("note: %s", blocked.get("note")).isEqualTo("FAILED");
        assertThat(blocked.get("passed").asBoolean()).isFalse();
        JsonNode minimum = null, regression = null;
        for (JsonNode c : blocked.get("checks")) {
            if (c.get("kind").asText().equals("minimum")) minimum = c;
            else regression = c;
        }
        assertThat(minimum.get("passed").asBoolean()).isFalse();
        assertThat(minimum.get("value").asDouble()).isLessThan(0.5);
        assertThat(regression.get("passed").asBoolean()).isFalse();
        assertThat(regression.get("reason").asText()).contains("Dropped").contains("significant").contains("blocked");
        assertThat(regression.get("baseline").asDouble()).isGreaterThan(regression.get("value").asDouble());
        assertThat(regression.get("p").asDouble()).isLessThan(0.05);

        // the same verdict as a pull request comment and as a CI test report
        String md = send(get("/api/ci/runs/" + bad + "?format=md"), s.account(), null).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(md).startsWith("### Glassbox quality gate: **failed**").contains("| **FAIL** |").contains("context_pass").contains("PR 7");
        String xml = send(get("/api/ci/runs/" + bad + "?format=junit"), s.account(), null).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var suite = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
        assertThat(suite.getAttribute("tests")).isEqualTo("2");
        assertThat(suite.getAttribute("failures")).isEqualTo("2");
        send(get("/api/ci/runs/" + bad + "?format=yaml"), s.account(), null).andExpect(status().isBadRequest());
    }

    @Test
    void strictModeJudgesTheLowerEndOfTheIntervalSoAPassMeansConfidentlyAtLeast() throws Exception {
        Setup s = setup();
        String lax = start(s, gateBody(s, ROOMY, "{\"recall@10\":0.99}", null, "\"name\":\"lax\""));
        String strict = start(s, gateBody(s, ROOMY, "{\"recall@10\":0.99}", null, "\"name\":\"strict\",\"strict\":true"));
        drainQueue();
        JsonNode l = result(s, lax), t = result(s, strict);
        assertThat(l.get("checks").get(0).get("value").asDouble()).isEqualTo(t.get("checks").get(0).get("value").asDouble());
        if (t.get("checks").get(0).get("ciLow").asDouble() < 0.99) {
            assertThat(t.get("state").asText()).isEqualTo("FAILED");
            assertThat(t.get("checks").get(0).get("reason").asText()).contains("lower end of the interval");
        }
    }

    @Test
    void aRunThatCannotBeJudgedIsAnErrorNotAPassOrAFailure() throws Exception {
        Setup s = setup();
        String id = start(s, gateBody(s, ROOMY, "{\"context_pass\":0.5}", null, null));
        ok(send(post("/api/experiments/" + id + "/cancel"), s.account(), null));
        JsonNode r = result(s, id);
        assertThat(r.get("state").asText()).isEqualTo("ERROR");
        assertThat(r.has("passed")).as("no verdict").isFalse();
        assertThat(r.get("note").asText()).isNotBlank();
        assertThat(send(get("/api/ci/runs/" + id + "?format=junit"), s.account(), null).andReturn().getResponse().getContentAsString()).contains("errors=\"1\"");
    }

    @Test
    void badRequestsAreRefusedBeforeAnythingIsStarted() throws Exception {
        Setup s = setup();
        int experiments = count(s.account(), "SELECT count(*) FROM experiments");
        send(post("/api/ci/run"), s.account(), gateBody(s, ROOMY, "{\"made_up\":0.5}", null, null)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Unknown metric")));
        send(post("/api/ci/run"), s.account(), gateBody(s, ROOMY, "{\"mrr\":1.5}", null, null)).andExpect(status().isBadRequest());
        send(post("/api/ci/run"), s.account(), gateBody(s, ROOMY, "{\"correctness\":0.5}", null, null)).andExpect(status().isBadRequest()); // a full-answer metric in a retrieval run
        send(post("/api/ci/run"), s.account(), gateBody(s, ROOMY, null, null, null)).andExpect(status().isBadRequest());
        send(post("/api/ci/run"), s.account(), "{\"testsetId\":\"" + s.testset() + "\",\"failOnRegression\":{\"mrr\":0.02}}").andExpect(status().isBadRequest());
        send(post("/api/ci/run"), s.account(), gateBody(s, ROOMY, "{\"mrr\":0.5}", null, "\"alpha\":2")).andExpect(status().isBadRequest());
        send(post("/api/ci/run"), s.account(), gateBody(s, ROOMY, "{\"mrr\":0.5}", null, "\"mode\":\"WHATEVER\"")).andExpect(status().isBadRequest());
        send(post("/api/ci/run"), s.account(), gateBody(s, "{\"name\":\"x\",\"mode\":\"TELEPATHY\"}", "{\"mrr\":0.5}", null, null)).andExpect(status().is4xxClientError());

        // a baseline from a different test set version cannot be compared question by question
        String otherSet = finalTestSet(s.account(), "another", 6);
        send(post("/api/ci/run"), s.account(), "{\"testsetId\":\"" + otherSet + "\",\"baselineRunId\":\"" + s.baselineRun() + "\",\"failOnRegression\":{\"mrr\":0.02},\"config\":" + ROOMY + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("different test set")));
        // a baseline that is not finished
        String unfinished = createExperiment(s.account(), s.testset(), "unfinished", "[" + ROOMY + "]").get("id").asText();
        String unfinishedRun = ok(send(get("/api/experiments/" + unfinished), s.account(), null)).get("runs").get(0).get("id").asText();
        send(post("/api/ci/run"), s.account(), gateBody(new Setup(s.account(), s.testset(), unfinishedRun), ROOMY, null, "{\"mrr\":0.02}", null)).andExpect(status().isConflict());

        assertThat(count(s.account(), "SELECT count(*) FROM experiments")).as("only the unfinished experiment was created").isEqualTo(experiments + 1);
        assertThat(count(s.account(), "SELECT count(*) FROM ci_gates")).isZero();
    }

    @Test
    void anApiKeyNeedsTheRunScopeToStartAGateAndAnyScopeToReadOne() throws Exception {
        Setup s = setup();
        String readKey = ok(send(post("/api/workspaces/" + s.account().workspace() + "/api-keys"), s.account(), "{\"label\":\"ro\",\"scopes\":[\"read\"]}")).get("key").asText();
        String runKey = ok(send(post("/api/workspaces/" + s.account().workspace() + "/api-keys"), s.account(), "{\"label\":\"ci\",\"scopes\":[\"run\"]}")).get("key").asText();
        String body = gateBody(s, ROOMY, "{\"mrr\":0.1}", null, null);

        mvc.perform(post("/api/ci/run").header("X-API-Key", readKey).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String id = json.readTree(mvc.perform(post("/api/ci/run").header("X-API-Key", runKey).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString()).get("id").asText();
        drainQueue();
        JsonNode viaRead = json.readTree(mvc.perform(get("/api/ci/runs/" + id).header("X-API-Key", readKey)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(viaRead.get("state").asText()).isEqualTo("PASSED");
        mvc.perform(post("/api/ci/run").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }

    @Test
    void aGateRunBelongsToItsWorkspaceAndIsAudited() throws Exception {
        Setup s = setup();
        String id = start(s, gateBody(s, ROOMY, "{\"mrr\":0.1}", null, null));
        Account other = register();
        send(get("/api/ci/runs/" + id), other, null).andExpect(status().isNotFound());
        send(post("/api/ci/run"), other, gateBody(s, ROOMY, "{\"mrr\":0.1}", null, null)).andExpect(status().is4xxClientError());
        assertThat(count(other, "SELECT count(*) FROM ci_gates")).isZero();
        assertThat(ok(send(get("/api/audit?action=ci."), s.account(), null)).get("items")).hasSize(1);
    }
}
