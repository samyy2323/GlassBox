package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;

/** Replacing a document: gold spans are re-located, the earlier configuration is re-run, and the report says what the change broke. */
class ChangeImpactApiTest extends EvalApiSupport {
    private String docId(Account a, String title) throws Exception {
        for (JsonNode d : ok(send(get("/api/documents"), a, null)).get("items")) if (d.get("title").asText().equals(title)) return d.get("id").asText();
        throw new AssertionError("no document " + title);
    }

    private void replace(Account a, String id, String markdown) throws Exception {
        mvc.perform(multipart(HttpMethod.PUT, "/api/documents/" + id).file(new MockMultipartFile("file", "Doc0.md", null, markdown.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace())).andExpect(status().is2xxSuccessful());
        drainQueue();
    }

    @Test
    void aReplacedDocumentRelocatesWhatSurvivesMarksTheRestStaleAndReRunsTheEarlierMeasurement() throws Exception {
        Account a = accountWithDocs(2, 5);
        String set = finalTestSet(a, "impact", 10);
        String first = createExperiment(a, set, "before", "[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]").get("id").asText();
        drainQueue();
        assertThat(experiment(a, first).get("status").asText()).isEqualTo("DONE");
        String doc = docId(a, "Doc0");

        // the new version keeps the first section and drops the other four
        replace(a, doc, markdown("Topic0", 1, 0));
        JsonNode report = ok(send(get("/api/documents/" + doc + "/versions/2/impact"), a, null));
        assertThat(report.get("status").asText()).as("note: %s", report.get("note")).isEqualTo("DONE");
        int aboutDoc = report.get("relocated").asInt() + report.get("stale").asInt();
        assertThat(aboutDoc).isGreaterThan(0);
        assertThat(report.get("stale").asInt()).as("sections that no longer exist cannot be found").isGreaterThan(0);
        assertThat(report.get("staleQuestions")).hasSize(report.get("stale").asInt());
        assertThat(report.get("testsetVersion").asInt()).isEqualTo(2);

        // the new test set version is final, holds the re-located spans, and the stale questions wait for review
        JsonNode newSet = ok(send(get("/api/testsets/" + report.get("testsetId").asText()), a, null));
        assertThat(newSet.get("status").asText()).isEqualTo("FINAL");
        assertThat(newSet.get("stats").get("byFlag").path("STALE").asInt()).isEqualTo(report.get("stale").asInt());
        assertThat(count(a, "SELECT count(*) FROM test_questions WHERE testset_id = '" + report.get("testsetId").asText() + "' AND review_status = 'PENDING'")).isEqualTo(report.get("stale").asInt());
        assertThat(ok(send(get("/api/testsets/" + set), a, null)).get("version").asInt()).as("the old version is untouched").isEqualTo(1);

        // the regression experiment re-ran the configuration that was measured before, and is compared with that run
        assertThat(report.get("experimentStatus").asText()).isEqualTo("DONE");
        JsonNode regression = experiment(a, report.get("experimentId").asText());
        assertThat(regression.get("kind").asText()).isEqualTo("REGRESSION");
        assertThat(regression.get("runs").get(0).get("config").get("retrieval").get("mode").asText()).isEqualTo("HYBRID");
        JsonNode verdict = report.get("verdict");
        assertThat(verdict.get("questions").asInt()).isGreaterThan(0);
        assertThat(verdict.get("delta").asDouble()).isEqualTo(verdict.get("after").asDouble() - verdict.get("before").asDouble(), org.assertj.core.api.Assertions.within(1e-9));
        assertThat(verdict.get("text").asText()).matches("(B beats A|A beats B) by .*|No significant difference .*");
        assertThat(report.get("passToFail").size()).isLessThanOrEqualTo(verdict.get("questions").asInt());

        // the paragraph diff
        JsonNode diff = report.get("diff");
        assertThat(diff.get("summary").get("removed").asInt()).isGreaterThan(0);
        assertThat(diff.get("from").asInt()).isEqualTo(1);
        JsonNode versions = ok(send(get("/api/documents/" + doc + "/versions"), a, null));
        assertThat(versions).hasSize(2);
        assertThat(versions.get(0).get("current").asBoolean()).isTrue();
        assertThat(ok(send(get("/api/documents/" + doc + "/versions/2/diff"), a, null)).get("ops").size()).isGreaterThan(1);
    }

    @Test
    void aReplacedDocumentNoTestSetAsksAboutHasNothingToRelocate() throws Exception {
        Account a = accountWithDocs(1, 3);
        String doc = docId(a, "Doc0");
        replace(a, doc, markdown("Topic0", 2, 0));
        JsonNode report = ok(send(get("/api/documents/" + doc + "/versions/2/impact"), a, null));
        assertThat(report.get("status").asText()).isEqualTo("NO_QUESTIONS");
        assertThat(report.get("diff").get("summary").get("removed").asInt()).isGreaterThan(0);
        assertThat(count(a, "SELECT count(*) FROM experiments")).isZero();
    }

    @Test
    void aDraftInProgressIsNotOverwrittenAndTheReportSaysWhy() throws Exception {
        Account a = accountWithDocs(1, 5);
        String set = finalTestSet(a, "drafting", 5);
        ok(send(post("/api/testsets/" + set + "/versions"), a, null));
        replace(a, docId(a, "Doc0"), markdown("Topic0", 2, 0));
        JsonNode report = ok(send(get("/api/documents/" + docId(a, "Doc0") + "/versions/2/impact"), a, null));
        assertThat(report.get("status").asText()).isEqualTo("SKIPPED");
        assertThat(report.get("note").asText()).contains("draft version in progress");
        assertThat(count(a, "SELECT count(*) FROM testsets")).as("no third version was made").isEqualTo(2);
    }

    @Test
    void theImpactOfAnotherWorkspacesDocumentIsNotFoundAndVersion1HasNoEarlierToCompare() throws Exception {
        Account a = accountWithDocs(1, 3);
        String doc = docId(a, "Doc0");
        send(get("/api/documents/" + doc + "/versions/1/diff"), a, null).andExpect(status().isNotFound());
        Account stranger = register();
        send(get("/api/documents/" + doc + "/versions"), stranger, null).andExpect(status().isNotFound());
        send(get("/api/documents/" + doc + "/versions/2/impact"), stranger, null).andExpect(status().isNotFound());
    }
}
