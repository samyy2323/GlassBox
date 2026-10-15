package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.auth.Hashing;
import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Reports: the snapshot, the PDF, the share link and everything a link must not be able to do. */
class ReportApiTest extends EvalApiSupport {
    private static final String TWO = "[{\"name\":\"tiny budget\",\"mode\":\"HYBRID\",\"tokenBudget\":100},{\"name\":\"roomy\",\"mode\":\"HYBRID\",\"tokenBudget\":8000}]";

    private String finishedExperiment(Account a) throws Exception {
        for (int i = 0; i < 2; i++) uploadOk(a, "Bulky" + i + ".md", bulkyMarkdown("Bulky" + i, 4, i).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        String set = finalTestSet(a, "reported", 6);
        String id = createExperiment(a, set, "budgets", TWO).get("id").asText();
        drainQueue();
        assertThat(experiment(a, id).get("status").asText()).isEqualTo("DONE");
        return id;
    }

    private JsonNode report(Account a, String experiment, boolean redact) throws Exception {
        return ok(send(post("/api/reports"), a, "{\"kind\":\"EXPERIMENT\",\"refId\":\"" + experiment + "\",\"redact\":" + redact + "}"));
    }

    private String pdfText(byte[] bytes) throws Exception {
        try (var doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private byte[] bytes(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    @Test
    void aReportFreezesTheNumbersTheVerdictsTheFailureHistogramAndTheLimitations() throws Exception {
        Account a = register();
        String exp = finishedExperiment(a);
        JsonNode meta = report(a, exp, false);
        assertThat(meta.get("kind").asText()).isEqualTo("EXPERIMENT");
        assertThat(meta.get("activeLinks").asInt()).isZero();

        JsonNode snap = ok(send(get("/api/reports/" + meta.get("id").asText()), a, null));
        assertThat(snap.get("title").asText()).isEqualTo("Experiment: budgets");
        assertThat(snap.get("summary").get(0).asText()).contains("questions from").contains("2 configurations");
        assertThat(snap.get("configs")).hasSize(2);
        assertThat(snap.get("metrics").get(0).get("rows").size()).isGreaterThan(3);
        assertThat(snap.get("comparisons").get(0).get("rows").get(0).get("verdict").asText()).matches("(B beats A|A beats B) by .*|No significant difference .*");
        assertThat(snap.get("failures").get("tiny budget").get("CONTEXT_MISS").asInt()).isGreaterThan(0);
        assertThat(snap.get("failures").get("roomy")).as("a run with no failures is present, empty").isEmpty();
        assertThat(snap.get("failedQuestions")).isNotEmpty();
        assertThat(snap.get("testset").get("name").asText()).isEqualTo("reported");
        assertThat(snap.get("limitations").toString()).contains("95% bootstrap");

        // the snapshot is frozen: later changes to the run do not alter it
        tx.workspace(a.workspace(), null, j -> j.update("UPDATE experiments SET name = 'renamed' WHERE id = ?::uuid", exp));
        assertThat(ok(send(get("/api/reports/" + meta.get("id").asText()), a, null)).get("title").asText()).isEqualTo("Experiment: budgets");
        assertThat(ok(send(get("/api/reports"), a, null))).hasSize(1);
    }

    @Test
    void thePdfHasTheNumbersAndRedactionRemovesTheQuestionTexts() throws Exception {
        Account a = register();
        String exp = finishedExperiment(a);
        JsonNode plain = report(a, exp, false), redacted = report(a, exp, true);
        byte[] pdf = bytes(send(get("/api/reports/" + plain.get("id").asText() + "/pdf"), a, null));
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String text = pdfText(pdf);
        assertThat(text).contains("Experiment: budgets").contains("Evidence in prompt").contains("Where the misses were lost").contains("context miss").contains("Limitations").contains("95%");
        String aQuestion = ok(send(get("/api/reports/" + plain.get("id").asText()), a, null)).get("failedQuestions").get(0).get("question").asText();
        assertThat(text.replaceAll("\\s+", " ")).contains(aQuestion);

        JsonNode snap = ok(send(get("/api/reports/" + redacted.get("id").asText()), a, null));
        assertThat(snap.get("redacted").asBoolean()).isTrue();
        assertThat(snap.has("failedQuestions")).isFalse();
        String redactedText = pdfText(bytes(send(get("/api/reports/" + redacted.get("id").asText() + "/pdf"), a, null)));
        assertThat(redactedText.replaceAll("\\s+", " ")).doesNotContain(aQuestion).contains("question texts redacted");
    }

    @Test
    void aShareLinkOpensOneSnapshotWithoutSigningInAndOnlyUntilItExpiresOrIsRevoked() throws Exception {
        Account a = register();
        String exp = finishedExperiment(a);
        String id = report(a, exp, false).get("id").asText();
        JsonNode link = ok(send(post("/api/reports/" + id + "/links"), a, "{\"days\":3}"));
        String token = link.get("token").asText();
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");

        // only the hash is stored, and the token appears nowhere else
        assertThat(count(a, "SELECT count(*) FROM report_links WHERE token_hash = '" + token + "'")).isZero();
        assertThat(count(a, "SELECT count(*) FROM report_links WHERE token_hash = '" + Hashing.sha256Hex(token) + "'")).isEqualTo(1);
        assertThat(count(a, "SELECT count(*) FROM audit_log WHERE entity LIKE '%" + token + "%' OR action LIKE '%" + token + "%'")).isZero();
        assertThat(ok(send(get("/api/reports/" + id + "/links"), a, null)).toString()).doesNotContain(token);

        // no Authorization header at all
        var open = mvc.perform(get("/api/public/reports/" + token)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(open.getHeader("Cache-Control")).contains("no-store");
        assertThat(open.getHeader("X-Robots-Tag")).isEqualTo("noindex");
        JsonNode shared = json.readTree(open.getContentAsString());
        assertThat(shared.get("snapshot").get("title").asText()).isEqualTo("Experiment: budgets");
        assertThat(shared.has("workspaceId")).isFalse();
        byte[] pdf = mvc.perform(get("/api/public/reports/" + token + "/pdf")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(pdfText(pdf)).contains("Experiment: budgets");
        assertThat(ok(send(get("/api/reports"), a, null)).get(0).get("activeLinks").asInt()).isEqualTo(1);

        // the same answer for every kind of bad token
        mvc.perform(get("/api/public/reports/not-a-token")).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/reports/" + "A".repeat(43))).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/reports/" + token.substring(0, 42) + (token.endsWith("A") ? "B" : "A"))).andExpect(status().isNotFound());

        // expired
        tx.workspace(a.workspace(), null, j -> j.update("UPDATE report_links SET expires_at = now() - interval '1 hour'"));
        mvc.perform(get("/api/public/reports/" + token)).andExpect(status().isNotFound());
        tx.workspace(a.workspace(), null, j -> j.update("UPDATE report_links SET expires_at = now() + interval '1 day'"));
        mvc.perform(get("/api/public/reports/" + token)).andExpect(status().isOk());

        // revoked
        send(delete("/api/reports/" + id + "/links/" + link.get("id").asText()), a, null).andExpect(status().isNoContent());
        mvc.perform(get("/api/public/reports/" + token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/public/reports/" + token + "/pdf")).andExpect(status().isNotFound());
        assertThat(ok(send(get("/api/reports/" + id + "/links"), a, null)).get(0).get("active").asBoolean()).isFalse();
        send(delete("/api/reports/" + id + "/links/" + link.get("id").asText()), a, null).andExpect(status().isNotFound());
    }

    @Test
    void aLinkCannotBeUsedToReachAnythingElse() throws Exception {
        Account a = register();
        String exp = finishedExperiment(a);
        String id = report(a, exp, false).get("id").asText();
        String token = ok(send(post("/api/reports/" + id + "/links"), a, null)).get("token").asText();
        // the token is not a credential for the API
        mvc.perform(get("/api/reports/" + id).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/experiments/" + exp).header("X-Report-Token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/public/reports/" + token + "/../../experiments/" + exp)).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/public/experiments/" + exp)).andExpect(status().isUnauthorized());
        // and writes through the public path do not exist
        mvc.perform(post("/api/public/reports/" + token)).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/public/reports/" + token)).andExpect(status().is4xxClientError());
    }

    @Test
    void reportsBelongToTheirWorkspaceAndViewersMayReadButNotShare() throws Exception {
        Account owner = register();
        String exp = finishedExperiment(owner);
        String id = report(owner, exp, false).get("id").asText();

        Account stranger = register();
        send(get("/api/reports/" + id), stranger, null).andExpect(status().isNotFound());
        send(get("/api/reports/" + id + "/pdf"), stranger, null).andExpect(status().isNotFound());
        send(post("/api/reports/" + id + "/links"), stranger, null).andExpect(status().isNotFound());
        send(delete("/api/reports/" + id), stranger, null).andExpect(status().isNotFound());
        assertThat(ok(send(get("/api/reports"), stranger, null))).isEmpty();
        send(post("/api/reports"), stranger, "{\"kind\":\"EXPERIMENT\",\"refId\":\"" + exp + "\"}").andExpect(status().isNotFound());

        Account viewer = register();
        mvc.perform(post("/api/workspaces/" + owner.workspace() + "/members").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewer.email() + "\",\"role\":\"VIEWER\"}")).andExpect(status().isCreated());
        Account asViewer = new Account(viewer.token(), owner.workspace(), viewer.email());
        ok(send(get("/api/reports/" + id), asViewer, null));
        send(post("/api/reports/" + id + "/links"), asViewer, null).andExpect(status().isForbidden());
        send(post("/api/reports"), asViewer, "{\"kind\":\"EXPERIMENT\",\"refId\":\"" + exp + "\"}").andExpect(status().isForbidden());
        send(post("/api/reports/" + id + "/links"), owner, "{\"days\":0}").andExpect(status().isBadRequest());
        send(post("/api/reports/" + id + "/links"), owner, "{\"days\":91}").andExpect(status().isBadRequest());
        send(post("/api/reports"), owner, "{\"kind\":\"SPREADSHEET\",\"refId\":\"" + exp + "\"}").andExpect(status().isBadRequest());
    }

    @Test
    void anUnfinishedRunCannotBeReportedAndAnAutotuneRunCan() throws Exception {
        Account a = accountWithDocs(3, 6);
        String set = finalTestSet(a, "tuned", 14);
        String queued = ok(send(post("/api/experiments"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"q\",\"configs\":[{\"name\":\"a\"}]}")).get("id").asText();
        send(post("/api/reports"), a, "{\"kind\":\"EXPERIMENT\",\"refId\":\"" + queued + "\"}").andExpect(status().isConflict());
        drainQueue();

        String tune = ok(send(post("/api/autotune"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"search\",\"stage1Candidates\":6,\"stage2Candidates\":6,\"finalists\":2,\"seed\":7}")).get("id").asText();
        send(post("/api/reports"), a, "{\"kind\":\"AUTOTUNE\",\"refId\":\"" + tune + "\"}").andExpect(status().isConflict());
        drainQueue();
        JsonNode meta = ok(send(post("/api/reports"), a, "{\"kind\":\"AUTOTUNE\",\"refId\":\"" + tune + "\"}"));
        JsonNode snap = ok(send(get("/api/reports/" + meta.get("id").asText()), a, null));
        assertThat(snap.get("headline").get("verdict").asText()).isNotBlank();
        assertThat(snap.get("pareto").get("points")).isNotEmpty();
        assertThat(snap.get("candidates").get(0).has("config")).as("configurations are described in words, not dumped").isFalse();
        String text = pdfText(bytes(send(get("/api/reports/" + meta.get("id").asText() + "/pdf"), a, null)));
        assertThat(text).contains("AutoTune: search").contains("Finalists and the baseline").contains("Quality against cost").contains("Limitations");
    }
}
