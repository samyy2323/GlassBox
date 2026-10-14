package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.ingest.ApiTestSupport;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Shared set-up for the eval lab integration tests: a corpus, a final test set, experiments. */
abstract class EvalApiSupport extends ApiTestSupport {
    static final String[] NOUNS = {"compressor", "alternator", "condenser", "regulator", "generator", "evaporator", "accumulator", "transformer",
            "thermostat", "calibration", "refrigerant", "lubricator", "separator", "exchanger", "insulation", "pressurizer", "dehumidifier",
            "extinguisher", "anemometer", "flowmeter"};

    /** Sections with their own vocabulary, so a question about one section is answered by exactly that section. */
    static String markdown(String topic, int sections, int salt) {
        StringBuilder sb = new StringBuilder("# " + topic + "\n\n");
        for (int i = 0; i < sections; i++) {
            String n1 = NOUNS[(salt * 7 + i * 2) % NOUNS.length], n2 = NOUNS[(salt * 7 + i * 2 + 1) % NOUNS.length];
            sb.append("## Part ").append(i).append(" of ").append(topic).append("\n\n")
                    .append("The ").append(n1).append(" unit controls the ").append(n2).append(" circuit and retries every failed task for the plant. ")
                    .append("The system reports its health to operators every day. The limit for part ").append(i).append(" is ").append(40 + i)
                    .append(" units per hour, and operators must record any breach in the shared logbook before the end of the shift. ")
                    .append("Unique marker ").append(UUID.randomUUID()).append(" ends this part of the guide for readers and auditors.\n\n");
        }
        return sb.toString();
    }

    Account accountWithDocs(int docs, int sections) throws Exception {
        Account a = register();
        for (int i = 0; i < docs; i++) uploadOk(a, "Doc" + i + ".md", markdown("Topic" + i, sections, i).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        return a;
    }

    // ------------------------------------------------------------------ helpers

    ResultActions send(MockHttpServletRequestBuilder b, Account a, String body) throws Exception {
        b = b.header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace());
        return mvc.perform(body == null ? b : b.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    /** A FINAL test set whose accepted questions all have one gold span. */
    String finalTestSet(Account a, String name, int count) throws Exception {
        String id = ok(send(post("/api/testsets/generate"), a, "{\"name\":\"" + name + "\",\"count\":" + count + ",\"seed\":11,\"mix\":{\"FACTUAL\":1}}")).get("testset").get("id").asText();
        drainQueue();
        for (JsonNode q : ok(send(get("/api/testsets/" + id + "/questions?limit=200"), a, null)).get("items")) {
            ok(send(patch("/api/questions/" + q.get("id").asText()), a, "{\"reviewStatus\":\"ACCEPTED\"}"));
        }
        ok(send(post("/api/testsets/" + id + "/finalize"), a, "{}"));
        return id;
    }

    static final String THREE = "[{\"name\":\"vector\",\"mode\":\"VECTOR\"},{\"name\":\"keyword\",\"mode\":\"KEYWORD\"},{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]";

    JsonNode createExperiment(Account a, String testsetId, String name, String configs) throws Exception {
        return ok(send(post("/api/experiments"), a, "{\"testsetId\":\"" + testsetId + "\",\"name\":\"" + name + "\",\"configs\":" + configs + "}"));
    }

    JsonNode experiment(Account a, String id) throws Exception {
        return ok(send(get("/api/experiments/" + id), a, null));
    }

    static JsonNode metric(JsonNode run, String metric, String slice, String method) {
        for (JsonNode m : run.get("metrics")) {
            if (m.get("metric").asText().equals(metric) && m.get("slice").asText().equals(slice) && m.get("method").asText().equals(method)) return m;
        }
        throw new AssertionError("no metric " + metric + "/" + slice + "/" + method);
    }

    int count(Account a, String sql) {
        return tx.workspace(a.workspace(), null, j -> j.queryForObject(sql, Integer.class));
    }
}
