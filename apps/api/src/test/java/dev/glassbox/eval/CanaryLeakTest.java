package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * SPEC 12, automated isolation test 3: workspace B holds canary strings in its documents, test set, feedback and a shared report; workspace A does everything a
 * user can do (chat, X-Ray, experiments, reports, public link, exports) and none of A's answers, traces, reports, responses or log lines may contain a canary.
 * The documents of A and B share their vocabulary on purpose, so a search that crossed the tenant line would find B's text.
 */
class CanaryLeakTest extends EvalApiSupport {
    private final List<String> seen = new ArrayList<>();

    private String body(Account a, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String requestBody) throws Exception {
        String s = send(b, a, requestBody).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        seen.add(s);
        return s;
    }

    private String pdfText(byte[] bytes) throws Exception {
        try (var doc = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void aCanaryOfWorkspaceBNeverReachesAnythingWorkspaceASeesOrLogs() throws Exception {
        String canary = "CANARY-" + UUID.randomUUID().toString().replace("-", "");
        String canaryFeedback = "CANARYFEEDBACK" + UUID.randomUUID().toString().replace("-", "");

        // ---- workspace B: same vocabulary as A, plus the canary in every document, in its test questions, in feedback and in a shared report
        Account b = register();
        for (int i = 0; i < 2; i++) {
            uploadOk(b, "B-secret-" + canary + i + ".md",
                    (markdown("Topic" + i, 4, i) + "\n\nThe vault access phrase of workspace B is " + canary + " and nobody else may read it.\n").getBytes(StandardCharsets.UTF_8));
        }
        drainQueue();
        String bSet = finalTestSet(b, "b-" + canary, 6);
        String bExperiment = createExperiment(b, bSet, "b-" + canary, "[{\"name\":\"b\",\"mode\":\"HYBRID\"}]").get("id").asText();
        drainQueue();
        String bTrace = null;
        for (JsonNode e : parseEvents(chat(b, b.workspace(), "what is the limit for part 0 of Topic0"))) {
            if (e.has("traceId")) bTrace = e.get("traceId").asText();
        }
        assertThat(bTrace).as("B's chat answer has a trace").isNotNull();
        ok(send(post("/api/traces/" + bTrace + "/feedback"), b, "{\"rating\":\"DOWN\",\"comment\":\"" + canaryFeedback + "\"}"));
        String bReport = ok(send(post("/api/reports"), b, "{\"kind\":\"EXPERIMENT\",\"refId\":\"" + bExperiment + "\",\"redact\":false}")).get("id").asText();
        String bToken = ok(send(post("/api/reports/" + bReport + "/links"), b, "{\"days\":7}")).get("token").asText();
        String bTestQuestion = ok(send(get("/api/testsets/" + bSet + "/questions?limit=5"), b, null)).get("items").get(0).get("id").asText();

        assertThat(ok(send(get("/api/documents?limit=100"), b, null)).toString()).as("the canary is really in B's data, so the check below can fail").contains(canary);
        assertThat(ok(send(get("/api/feedback"), b, null)).toString()).contains(canaryFeedback);

        // ---- workspace A: its own corpus, same words
        Account a = accountWithDocs(2, 4);
        String aSet = finalTestSet(a, "a-set", 6);

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(logs);
        try {
            // chat, with questions that would match B's text if the search crossed the tenant line
            for (String q : List.of("what is the limit for part 0 of Topic0", "vault access phrase", "who may read the access phrase of workspace B", "CANARY")) {
                for (var event : chat(a, a.workspace(), q)) seen.add(String.valueOf(event.data()));
            }
            // A's own trace, X-Ray and feedback list
            String aTrace = null;
            for (JsonNode e : parseEvents(chat(a, a.workspace(), "what is the limit for part 1 of Topic1"))) if (e.has("traceId")) aTrace = e.get("traceId").asText();
            body(a, get("/api/traces/" + aTrace), null);
            body(a, get("/api/traces/" + aTrace + "/xray"), null);
            ok(send(post("/api/traces/" + aTrace + "/feedback"), a, "{\"rating\":\"DOWN\",\"comment\":\"not helpful\"}"));
            body(a, get("/api/feedback"), null);
            body(a, get("/api/feedback/summary"), null);

            // an experiment, its results, diagnosis and a report with its PDF and a public link
            String aExperiment = createExperiment(a, aSet, "a-exp", "[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"},{\"name\":\"vector\",\"mode\":\"VECTOR\"}]").get("id").asText();
            drainQueue();
            assertThat(experiment(a, aExperiment).get("status").asText()).isEqualTo("DONE");
            body(a, get("/api/experiments/" + aExperiment), null);
            body(a, get("/api/experiments"), null);
            body(a, post("/api/experiments/estimate"), "{\"testsetId\":\"" + aSet + "\",\"configs\":[{\"name\":\"x\"}]}");
            String aReport = ok(send(post("/api/reports"), a, "{\"kind\":\"EXPERIMENT\",\"refId\":\"" + aExperiment + "\",\"redact\":false}")).get("id").asText();
            String aToken = ok(send(post("/api/reports/" + aReport + "/links"), a, "{\"days\":7}")).get("token").asText();
            byte[] pdf = send(get("/api/reports/" + aReport + "/pdf"), a, null).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            seen.add(pdfText(pdf));
            seen.add(mvc.perform(get("/api/public/reports/" + aToken)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            byte[] publicPdf = mvc.perform(get("/api/public/reports/" + aToken + "/pdf")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            seen.add(pdfText(publicPdf));

            // everything else A can list
            for (String path : List.of("/api/documents?limit=100", "/api/testsets", "/api/testsets/" + aSet + "/questions?limit=200", "/api/budget", "/api/reports",
                    "/api/autotune", "/api/workspaces")) {
                body(a, get(path), null);
            }

            // B's identifiers are not reachable with A's credentials: not found, and nothing of B in the error body
            for (String path : List.of("/api/testsets/" + bSet, "/api/experiments/" + bExperiment, "/api/traces/" + bTrace, "/api/reports/" + bReport,
                    "/api/reports/" + bReport + "/pdf")) {
                var response = send(get(path), a, null).andReturn().getResponse();
                assertThat(response.getStatus()).as(path).isIn(403, 404);
                seen.add(response.getContentAsString(StandardCharsets.UTF_8));
            }
            assertThat(send(patch("/api/questions/" + bTestQuestion), a, "{\"reviewStatus\":\"REJECTED\"}").andReturn().getResponse().getStatus()).as("editing B's question").isIn(403, 404);
            // and A's token does not open B's link by another route either
            mvc.perform(get("/api/reports/" + bReport).header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", b.workspace()))
                    .andExpect(status().is4xxClientError());
        } finally {
            root.detachAppender(logs);
        }

        // ---- the check itself
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent e : logs.list) {
            lines.add(e.getFormattedMessage());
            if (e.getThrowableProxy() != null) lines.add(ThrowableProxyUtil.asString(e.getThrowableProxy()));
        }
        assertThat(seen).as("responses collected for workspace A").hasSizeGreaterThan(20);
        assertThat(lines).as("log lines produced while workspace A worked").isNotEmpty();
        for (String secret : List.of(canary, canaryFeedback, bToken)) {
            assertThat(seen).as("a response, trace or report of workspace A contains B's string").noneMatch(s -> s != null && s.contains(secret));
            assertThat(lines).as("a log line contains B's string").noneMatch(s -> s.contains(secret));
        }
    }

    private List<JsonNode> parseEvents(List<Sse> events) {
        List<JsonNode> out = new ArrayList<>();
        for (Sse e : events) if (e.data() != null) out.add(e.data());
        return out;
    }
}
