package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.glassbox.stats.Cohen;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Judge calibration: a human labels a sample without seeing the judge's verdict; agreement, kappa and the uncalibrated badge follow. */
class CalibrationApiTest extends EvalApiSupport {
    static final String FOUR = "[{\"name\":\"hybrid\"},{\"name\":\"vector\",\"mode\":\"VECTOR\"},{\"name\":\"keyword\",\"mode\":\"KEYWORD\"},{\"name\":\"loose\",\"kFused\":30,\"rrfK\":30}]";
    static final String[] CORRECTNESS = {"correct", "partial", "wrong"}, SUPPORT = {"SUPPORTED", "PARTIAL", "UNSUPPORTED"};

    /** A finished full experiment with 4 runs, whose judge verdicts are then replaced by a known, lopsided mix (about 60% / 20% / 20%). */
    record Setup(Account account, String experiment, int results) {}

    Setup judgedWorkspace() throws Exception {
        Account a = accountWithDocs(2, 6);
        String set = finalTestSet(a, "calibrate", 8);
        FullExperimentApiTest.answerFromContext();
        JsonNode created = ok(send(post("/api/experiments"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"judged\",\"mode\":\"FULL\",\"configs\":" + FOUR + "}"));
        drainQueue();
        List<UUID> ids = tx.workspace(a.workspace(), null, j -> j.queryForList("SELECT id FROM run_results ORDER BY id", UUID.class));
        for (int i = 0; i < ids.size(); i++) {
            int band = i % 10 < 6 ? 0 : i % 10 < 8 ? 1 : 2;
            ObjectNode claims = json.createObjectNode();
            claims.put("verifier", ClaimVerifier.VERSION);
            claims.put("rubricVersion", Judge.RUBRIC_VERSION);
            ArrayNode list = claims.putArray("claims");
            ObjectNode claim = list.addObject();
            claim.put("text", "A claim number " + i + " in the answer.");
            claim.put("verdict", SUPPORT[band]);
            claim.put("source", "NLI");
            claim.put("premiseLabel", "c1");
            ObjectNode judge = claims.putObject("judge");
            judge.putObject("correctness").put("verdict", CORRECTNESS[band]).put("reason", "scripted");
            judge.putObject("relevance").put("verdict", new String[] {"yes", "partly", "no"}[band]).put("reason", "scripted");
            String text = claims.toString();
            UUID id = ids.get(i);
            tx.workspace(a.workspace(), null, j -> j.update("UPDATE run_results SET claims = ?::jsonb WHERE id = ?", text, id));
        }
        return new Setup(a, created.get("id").asText(), ids.size());
    }

    JsonNode sample(Account a, String metric, int size) throws Exception {
        return ok(send(post("/api/calibration/sample"), a, "{\"metric\":\"" + metric + "\",\"size\":" + size + "}"));
    }

    JsonNode summary(Account a) throws Exception {
        return ok(send(get("/api/calibration/summary"), a, null));
    }

    static JsonNode metricSummary(JsonNode summary, String metric) {
        for (JsonNode m : summary.get("metrics")) if (m.get("metric").asText().equals(metric)) return m;
        throw new AssertionError(metric);
    }

    /** What the judge said about each sampled item, read straight from the database (the API never sends it). */
    Map<UUID, String> judgeLabels(Account a, JsonNode items) {
        Map<UUID, String> out = new HashMap<>();
        for (JsonNode item : items) {
            UUID id = UUID.fromString(item.get("labelId").asText());
            out.put(id, tx.workspace(a.workspace(), null, j -> j.queryForObject("SELECT judge_label FROM judge_labels WHERE id = ?", String.class, id)));
        }
        return out;
    }

    String labelsBody(Map<UUID, String> human) {
        List<String> parts = new ArrayList<>();
        human.forEach((id, label) -> parts.add("{\"labelId\":\"" + id + "\",\"humanLabel\":\"" + label + "\"}"));
        return "{\"labels\":[" + String.join(",", parts) + "]}";
    }

    @Test
    void theSampleIsBalancedAcrossTheJudgesVerdictsAndNeverShowsTheVerdict() throws Exception {
        Setup s = judgedWorkspace();
        JsonNode sample = sample(s.account(), "correctness", 12);
        assertThat(sample.get("items")).hasSize(12);
        assertThat(sample.get("target").asInt()).isEqualTo(30);
        assertThat(sample.get("stillUnlabeled").asInt()).isEqualTo(s.results() - 12);

        Map<UUID, String> hidden = judgeLabels(s.account(), sample.get("items"));
        Map<String, Integer> byVerdict = new HashMap<>();
        hidden.values().forEach(v -> byVerdict.merge(v, 1, Integer::sum));
        assertThat(byVerdict).as("60/20/20 in the data, but 4/4/4 in the sample").containsEntry("correct", 4).containsEntry("partial", 4).containsEntry("wrong", 4);

        for (JsonNode item : sample.get("items")) {
            assertThat(item.has("judgeLabel")).isFalse();
            assertThat(item.has("verdict")).isFalse();
            assertThat(item.get("question").asText()).isNotBlank();
            assertThat(item.get("expectedAnswer").asText()).isNotBlank();
            assertThat(item.get("answer").asText()).isNotBlank();
            assertThat(item.get("options")).extracting(JsonNode::asText).containsExactly("correct", "partial", "wrong");
        }
        assertThat(sample.toString()).as("neither as a field nor as a value").doesNotContain("scripted").doesNotContain("judge");

        // asking again does not create the rows a second time
        sample(s.account(), "correctness", 12);
        assertThat(count(s.account(), "SELECT count(*) FROM judge_labels WHERE metric = 'correctness'")).isEqualTo(s.results());
    }

    @Test
    void aClaimIsShownWithThePassageItWasCheckedAgainst() throws Exception {
        Setup s = judgedWorkspace();
        JsonNode sample = sample(s.account(), "faithfulness", 10);
        assertThat(sample.get("items")).hasSize(10);
        for (JsonNode item : sample.get("items")) {
            assertThat(item.get("claim").asText()).startsWith("A claim number");
            assertThat(item.get("evidence").asText()).as("the chunk the statement was checked against").isNotBlank().doesNotContain("no longer available");
            assertThat(item.get("options")).extracting(JsonNode::asText).containsExactly("supported", "partial", "unsupported");
            assertThat(item.get("itemKey").asText()).startsWith("claim:");
        }
        JsonNode relevance = sample(s.account(), "relevance", 6);
        assertThat(relevance.get("items").get(0).has("expectedAnswer")).as("relevance does not need the expected answer").isFalse();
    }

    @Test
    void labelsGiveAgreementKappaTheConfusionMatrixAndPerVerdictScores() throws Exception {
        Setup s = judgedWorkspace();
        JsonNode sample = sample(s.account(), "correctness", 12);
        Map<UUID, String> judge = judgeLabels(s.account(), sample.get("items"));
        Map<UUID, String> human = new HashMap<>(judge);
        int flipped = 0;
        for (UUID id : judge.keySet()) {
            if (flipped < 3 && judge.get(id).equals("correct")) {
                human.put(id, "wrong"); // the judge was too generous three times
                flipped++;
            }
        }
        JsonNode updated = ok(send(post("/api/calibration/labels"), s.account(), labelsBody(human)));
        assertThat(updated).hasSize(1);

        List<String> h = new ArrayList<>(), j = new ArrayList<>();
        for (UUID id : judge.keySet()) {
            h.add(human.get(id));
            j.add(judge.get(id));
        }
        Cohen.Result expected = Cohen.compare(h, j);
        JsonNode m = metricSummary(summary(s.account()), "correctness");
        assertThat(m.get("n").asInt()).isEqualTo(12);
        assertThat(m.get("agreement").asDouble()).isCloseTo(expected.agreement(), within(1e-12));
        assertThat(m.get("agreement").asDouble()).isCloseTo(9.0 / 12, within(1e-12));
        assertThat(m.get("kappa").asDouble()).isCloseTo(expected.kappa(), within(1e-12));
        assertThat(m.get("status").asText()).as("12 labels is not enough to trust a kappa").isEqualTo("uncalibrated");
        assertThat(m.get("needsMoreLabels").asBoolean()).isTrue();
        assertThat(m.get("labels")).extracting(JsonNode::asText).containsExactly("correct", "partial", "wrong");
        assertThat(m.get("confusion")).hasSize(3);
        int diagonal = 0;
        for (int i = 0; i < 3; i++) diagonal += m.get("confusion").get(i).get(i).asInt();
        assertThat(diagonal).isEqualTo(9);
        assertThat(m.get("precision").get("correct").asDouble()).as("the judge called 4 answers correct and a human agreed on 1").isCloseTo(1.0 / 4, within(1e-12));
        assertThat(m.get("recall").get("wrong").asDouble()).as("a human found 7 wrong answers, the judge only 4 of them").isCloseTo(4.0 / 7, within(1e-12));
        assertThat(m.get("unlabeled").asInt()).isEqualTo(s.results() - 12);
        assertThat(metricSummary(summary(s.account()), "faithfulness").get("status").asText()).as("other metrics are separate").isEqualTo("unknown");
    }

    @Test
    void aJudgeIsCalibratedOnlyWithEnoughLabelsAndAgreementAndLosesItWhenLabelsDisagree() throws Exception {
        Setup s = judgedWorkspace();
        assertThat(s.results()).isGreaterThanOrEqualTo(30);
        JsonNode sample = sample(s.account(), "correctness", 50);
        Map<UUID, String> judge = judgeLabels(s.account(), sample.get("items"));
        assertThat(judge).hasSize(s.results());

        ok(send(post("/api/calibration/labels"), s.account(), labelsBody(judge))); // the human agrees with the judge everywhere
        JsonNode m = metricSummary(summary(s.account()), "correctness");
        assertThat(m.get("kappa").asDouble()).isEqualTo(1.0);
        assertThat(m.get("status").asText()).isEqualTo("calibrated");
        assertThat(m.get("needsMoreLabels").asBoolean()).isFalse();
        JsonNode experiment = experiment(s.account(), s.experiment());
        assertThat(experiment.get("judge").get("metrics").get("correctness").get("status").asText()).isEqualTo("calibrated");
        assertThat(experiment.get("judge").get("metrics").get("correctness").get("kappa").asDouble()).isEqualTo(1.0);
        assertThat(experiment.get("judge").get("metrics").get("relevance").get("status").asText()).isEqualTo("unknown");

        // the human changes their mind on most items: kappa collapses and the badge comes back
        Map<UUID, String> reversed = new HashMap<>();
        judge.forEach((id, label) -> reversed.put(id, label.equals("correct") ? "wrong" : label.equals("wrong") ? "correct" : "partial"));
        ok(send(post("/api/calibration/labels"), s.account(), labelsBody(reversed)));
        JsonNode after = experiment(s.account(), s.experiment()).get("judge").get("metrics").get("correctness");
        assertThat(after.get("status").asText()).isEqualTo("uncalibrated");
        assertThat(after.get("kappa").asDouble()).isLessThan(Cohen.TRUSTED_KAPPA);
        assertThat(count(s.account(), "SELECT count(*) FROM judge_calibrations WHERE metric = 'correctness'")).as("one saved row per judge, rubric and metric").isEqualTo(1);
    }

    @Test
    void calibrationBelongsToOneJudgeModelAndRubricVersion() throws Exception {
        Setup s = judgedWorkspace();
        Account a = s.account();
        // a good calibration of some other judge model, and of this model under an older rubric, must not vouch for the current judge
        tx.workspace(a.workspace(), null, j -> {
            j.update("INSERT INTO judge_calibrations (workspace_id, judge_model, rubric_version, metric, n, agreement, kappa) VALUES (?, 'some-other-model', 'v1', 'correctness', 50, 0.95, 0.9)", a.workspace());
            j.update("INSERT INTO judge_calibrations (workspace_id, judge_model, rubric_version, metric, n, agreement, kappa) VALUES (?, 'fake-llm', 'v0', 'correctness', 50, 0.95, 0.9)", a.workspace());
            return null;
        });
        assertThat(experiment(a, s.experiment()).get("judge").get("metrics").get("correctness").get("status").asText()).isEqualTo("unknown");
        assertThat(metricSummary(summary(a), "correctness").get("n").asInt()).isZero();
        assertThat(metricSummary(summary(a), "correctness").has("kappa")).isFalse();
    }

    @Test
    void itemsGradedByAnOlderProcedureAreNotMixedIntoTheCurrentCalibration() throws Exception {
        Setup s = judgedWorkspace();
        Account a = s.account();
        // pretend half of the results were verified by the first version of the procedure and judged under an older rubric
        List<UUID> old = tx.workspace(a.workspace(), null, j -> j.queryForList("SELECT id FROM run_results ORDER BY id LIMIT 12", UUID.class));
        for (UUID id : old) {
            tx.workspace(a.workspace(), null, j -> j.update("UPDATE run_results SET claims = jsonb_set(jsonb_set(claims, '{verifier}', '\"windows-v1\"'), '{rubricVersion}', '\"v0\"') WHERE id = ?", id));
        }
        assertThat(sample(a, "faithfulness", 100).get("items")).as("12 results come from another verifier version").hasSize(s.results() - 12);
        assertThat(sample(a, "correctness", 100).get("items")).as("and from another rubric version").hasSize(s.results() - 12);
        // results from before versions were recorded count as the first rubric and as a legacy verifier
        tx.workspace(a.workspace(), null, j -> j.update("UPDATE run_results SET claims = claims - 'verifier' - 'rubricVersion' WHERE id = ?", old.get(0)));
        assertThat(sample(a, "faithfulness", 100).get("items")).hasSize(s.results() - 12);
        assertThat(sample(a, "correctness", 100).get("items")).as("an unversioned result is rubric v1, the current one").hasSize(s.results() - 11);
    }

    @Test
    void inputsRolesAndWorkspacesAreChecked() throws Exception {
        Setup s = judgedWorkspace();
        Account a = s.account();
        send(post("/api/calibration/sample"), a, "{\"metric\":\"nonsense\"}").andExpect(status().isBadRequest());
        send(post("/api/calibration/sample"), a, "{\"metric\":\"correctness\",\"size\":4}").andExpect(status().isBadRequest());
        send(post("/api/calibration/sample"), a, "{\"metric\":\"correctness\",\"size\":101}").andExpect(status().isBadRequest());
        send(post("/api/calibration/labels"), a, "{\"labels\":[]}").andExpect(status().isBadRequest());

        JsonNode sample = sample(a, "correctness", 5);
        String id = sample.get("items").get(0).get("labelId").asText();
        send(post("/api/calibration/labels"), a, "{\"labels\":[{\"labelId\":\"" + id + "\",\"humanLabel\":\"maybe\"}]}").andExpect(status().isBadRequest());
        send(post("/api/calibration/labels"), a, "{\"labels\":[{\"labelId\":\"" + id + "\",\"humanLabel\":\"yes\"}]}").andExpect(status().isBadRequest()); // a relevance word for a correctness item
        send(post("/api/calibration/labels"), a, "{\"labels\":[{\"labelId\":\"" + UUID.randomUUID() + "\",\"humanLabel\":\"correct\"}]}").andExpect(status().isNotFound());
        assertThat(count(a, "SELECT count(*) FROM judge_labels WHERE human_label IS NOT NULL")).as("a rejected request labels nothing").isZero();

        Account viewer = register();
        mvc.perform(post("/api/workspaces/" + a.workspace() + "/members").header("Authorization", "Bearer " + a.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        Account asViewer = new Account(viewer.token(), a.workspace(), viewer.email());
        ok(send(get("/api/calibration/summary"), asViewer, null));
        send(post("/api/calibration/sample"), asViewer, "{\"metric\":\"correctness\"}").andExpect(status().isForbidden());
        send(post("/api/calibration/labels"), asViewer, "{\"labels\":[{\"labelId\":\"" + id + "\",\"humanLabel\":\"correct\"}]}").andExpect(status().isForbidden());

        Account stranger = register();
        send(post("/api/calibration/labels"), stranger, "{\"labels\":[{\"labelId\":\"" + id + "\",\"humanLabel\":\"correct\"}]}").andExpect(status().isNotFound());
        assertThat(sample(stranger, "correctness", 10).get("items")).as("nothing judged in this workspace").isEmpty();
        assertThat(metricSummary(summary(stranger), "correctness").get("n").asInt()).isZero();
        mvc.perform(get("/api/calibration/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void theSummaryNamesTheJudgeAndWarnsThatItGradesItsOwnAnswers() throws Exception {
        Account a = register();
        JsonNode s = summary(a);
        assertThat(s.get("judgeModel").asText()).isEqualTo("fake-llm");
        assertThat(s.get("rubricVersion").asText()).isEqualTo(Judge.RUBRIC_VERSION);
        assertThat(s.get("minimumLabels").asInt()).isEqualTo(30);
        assertThat(s.get("trustedKappa").asDouble()).isEqualTo(0.6);
        assertThat(s.get("warnings").toString()).contains("also judges");
        assertThat(s.get("metrics")).hasSize(3);
        assertThat(metricSummary(s, "faithfulness").get("judgeModel").asText()).as("claims are checked by the NLI model together with the judge").contains("fake-nli");
    }
}
