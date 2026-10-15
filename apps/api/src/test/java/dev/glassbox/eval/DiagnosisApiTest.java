package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The diagnosis report through the real API: what the runner records for each gold span, how it is classified and what is advised. */
class DiagnosisApiTest extends EvalApiSupport {
    private Account accountWithBulkyDocs() throws Exception {
        Account a = register();
        for (int i = 0; i < 2; i++) uploadOk(a, "Bulky" + i + ".md", bulkyMarkdown("Bulky" + i, 4, i).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        return a;
    }

    private JsonNode diagnosis(Account a, String experiment, String run) throws Exception {
        return ok(send(get("/api/experiments/" + experiment + "/diagnosis?run=" + run), a, null));
    }

    @Test
    void aBudgetThatCannotHoldAChunkMakesEveryFailureAContextMissAndTheReportSaysSo() throws Exception {
        Account a = accountWithBulkyDocs();
        String set = finalTestSet(a, "bulky", 6);
        JsonNode created = createExperiment(a, set, "budgets",
                "[{\"name\":\"tiny budget\",\"mode\":\"HYBRID\",\"tokenBudget\":100},{\"name\":\"roomy\",\"mode\":\"HYBRID\",\"tokenBudget\":8000}]");
        drainQueue();
        JsonNode done = experiment(a, created.get("id").asText());
        int questions = done.get("questionCount").asInt();

        JsonNode tiny = diagnosis(a, done.get("id").asText(), done.get("runs").get(0).get("id").asText());
        assertThat(tiny.get("questions").asInt()).isEqualTo(questions);
        assertThat(tiny.get("failed").asInt()).isEqualTo(questions);
        assertThat(tiny.get("byStage").get("CONTEXT_MISS").asInt()).isEqualTo(questions);
        assertThat(tiny.get("byStage").get("RETRIEVAL_MISS").asInt()).isZero();
        JsonNode rec = tiny.get("recommendations").get(0);
        assertThat(rec.get("stage").asText()).isEqualTo("CONTEXT_MISS");
        assertThat(rec.get("percent").asInt()).isEqualTo(100);
        assertThat(rec.get("text").asText()).startsWith("100% of failures are context misses");
        assertThat(rec.get("change").get("tokenBudget").asInt()).isEqualTo(200);
        assertThat(tiny.get("failures")).hasSize(questions);
        assertThat(tiny.get("failures").get(0).get("stage").asText()).isEqualTo("CONTEXT_MISS");

        // what the runner recorded for the gold span: found by the search, ranked, cut by the budget
        String runId = done.get("runs").get(0).get("id").asText();
        JsonNode result = ok(send(get("/api/experiments/" + done.get("id").asText() + "/results?run=" + runId), a, null)).get(0);
        assertThat(result.get("failureStage").asText()).isEqualTo("CONTEXT_MISS");
        String detail = tx.workspace(a.workspace(), null, j -> j.queryForObject("SELECT failure_detail::text FROM run_results WHERE run_id = ?::uuid AND question_id = ?::uuid",
                String.class, runId, result.get("questionId").asText()));
        JsonNode span = json.readTree(detail).get("spans").get(0);
        assertThat(span.get("finalRank").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(span.get("inRanked").asBoolean()).isTrue();
        assertThat(span.get("inContext").asBoolean()).isFalse();
        assertThat(span.get("dropped").asText()).isEqualTo("BUDGET");

        // the roomy run fails nothing
        JsonNode roomy = diagnosis(a, done.get("id").asText(), done.get("runs").get(1).get("id").asText());
        assertThat(roomy.get("failed").asInt()).isZero();
        assertThat(roomy.get("recommendations")).isEmpty();
    }

    @Test
    void failuresAreCountedByStageAndQuestionType() throws Exception {
        Account a = accountWithDocs(2, 4);
        String set = finalTestSet(a, "stages", 6);
        JsonNode created = createExperiment(a, set, "stages", "[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]");
        drainQueue();
        String id = created.get("id").asText(), runId = experiment(a, id).get("runs").get(0).get("id").asText();
        List<String> questionIds = new ArrayList<>();
        ok(send(get("/api/experiments/" + id + "/results?run=" + runId), a, null)).forEach(r -> questionIds.add(r.get("questionId").asText()));
        assertThat(questionIds.size()).isGreaterThanOrEqualTo(4);

        // overwrite the stored labels with a known mix (the classifier itself is covered by its decision table)
        String[] stages = {"RANKING_MISS", "RANKING_MISS", "RETRIEVAL_MISS", "GENERATION_MISS"};
        String[] details = {null, null, null, "{\"sub\":\"UNFAITHFUL\"}"};
        tx.workspace(a.workspace(), null, j -> {
            for (int i = 0; i < 4; i++) {
                j.update("UPDATE run_results SET failure_stage = ?, failure_detail = ?::jsonb WHERE run_id = ?::uuid AND question_id = ?::uuid", stages[i], details[i], runId, questionIds.get(i));
            }
            for (int i = 4; i < questionIds.size(); i++) j.update("UPDATE run_results SET failure_stage = NULL WHERE run_id = ?::uuid AND question_id = ?::uuid", runId, questionIds.get(i));
            return null;
        });

        JsonNode d = diagnosis(a, id, runId);
        assertThat(d.get("failed").asInt()).isEqualTo(4);
        assertThat(d.get("byStage").get("RANKING_MISS").asInt()).isEqualTo(2);
        assertThat(d.get("byStage").get("RETRIEVAL_MISS").asInt()).isEqualTo(1);
        assertThat(d.get("bySub").get("UNFAITHFUL").asInt()).isEqualTo(1);
        int byType = 0;
        for (JsonNode n : d.get("byStageAndType").get("RANKING_MISS")) byType += n.asInt();
        assertThat(byType).isEqualTo(2);
        assertThat(d.get("recommendations").get(0).get("stage").asText()).isEqualTo("RANKING_MISS");
        assertThat(d.get("recommendations").get(0).get("text").asText()).isEqualTo("50% of failures are ranking misses, so add a reranker.");
        assertThat(d.get("recommendations").get(0).get("change").get("reranker").asText()).isEqualTo("CROSS_ENCODER");
    }

    @Test
    void theReportIsScopedToTheTenantAndAnUnknownRunIsNotFound() throws Exception {
        Account a = accountWithDocs(1, 3);
        String set = finalTestSet(a, "tenant", 3);
        JsonNode created = createExperiment(a, set, "t", "[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]");
        drainQueue();
        String id = created.get("id").asText(), runId = experiment(a, id).get("runs").get(0).get("id").asText();

        Account other = register();
        send(get("/api/experiments/" + id + "/diagnosis?run=" + runId), other, null).andExpect(status().isNotFound());
        send(get("/api/experiments/" + id + "/diagnosis?run=00000000-0000-0000-0000-000000000000"), a, null).andExpect(status().isNotFound());
    }
}
