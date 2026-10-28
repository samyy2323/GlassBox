package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Test sets as JSON lines: the frozen test set of a CI gate is committed next to the documents and loaded into a fresh workspace. */
class TestSetPortApiTest extends EvalApiSupport {
    private static final List<String> DOCS = List.of(markdown("Topic0", 4, 0), markdown("Topic1", 4, 1));

    private Account withDocs(String preface) throws Exception {
        Account a = register();
        for (int i = 0; i < DOCS.size(); i++) uploadOk(a, "Doc" + i + ".md", (preface + DOCS.get(i)).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        return a;
    }

    private String export(Account a, String set) throws Exception {
        return send(get("/api/testsets/" + set + "/export"), a, null).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.ResultActions importInto(Account a, String file, String query) throws Exception {
        return mvc.perform(post("/api/testsets/import" + query).header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace())
                .contentType("application/x-ndjson").content(file));
    }

    @Test
    void aSetExportedFromOneWorkspaceLoadsIntoAnotherThatHoldsTheSameDocumentsAndCanBeMeasured() throws Exception {
        Account source = withDocs("");
        String set = finalTestSet(source, "frozen", 8);
        String file = export(source, set);

        String[] lines = file.split("\n");
        JsonNode header = json.readTree(lines[0]);
        assertThat(header.get("format").asText()).isEqualTo("glassbox-testset");
        assertThat(header.get("questions").asInt()).isEqualTo(lines.length - 1).isGreaterThan(0);
        JsonNode first = json.readTree(lines[1]);
        assertThat(first.get("evidence").get(0).get("document").asText()).startsWith("Doc");
        assertThat(first.get("evidence").get(0).get("text").asText()).isNotBlank();
        assertThat(file).as("ids of the source workspace do not travel").doesNotContain(source.workspace().toString());

        Account target = withDocs("");
        JsonNode result = json.readTree(importInto(target, file, "?finalize=true").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(result.get("imported").asInt()).isEqualTo(lines.length - 1);
        assertThat(result.get("skipped")).isEmpty();
        assertThat(result.get("testset").get("status").asText()).isEqualTo("FINAL");
        assertThat(result.get("testset").get("name").asText()).isEqualTo("frozen");

        // the imported set is usable: evidence points at the target's own documents, stable ids are kept, and it can be measured
        String imported = result.get("testset").get("id").asText();
        Set<String> sourceIds = new HashSet<>(), targetIds = new HashSet<>();
        for (JsonNode q : ok(send(get("/api/testsets/" + set + "/questions?limit=200"), source, null)).get("items")) sourceIds.add(q.get("stableId").asText());
        List<String> evidenceDocs = new ArrayList<>();
        for (JsonNode q : ok(send(get("/api/testsets/" + imported + "/questions?limit=200"), target, null)).get("items")) {
            targetIds.add(q.get("stableId").asText());
            for (JsonNode e : q.get("evidence")) evidenceDocs.add(e.get("documentId").asText());
        }
        assertThat(targetIds).isEqualTo(sourceIds);
        assertThat(evidenceDocs).isNotEmpty();
        assertThat(count(target, "SELECT count(*) FROM documents WHERE id::text = ANY (?::text[])".replace("?::text[]", "ARRAY['" + String.join("','", evidenceDocs) + "']"))).isGreaterThan(0);
        String exp = createExperiment(target, imported, "on the import", "[{\"name\":\"hybrid\",\"mode\":\"HYBRID\"}]").get("id").asText();
        drainQueue();
        JsonNode done = experiment(target, exp);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(metric(done.get("runs").get(0), "recall@10", "all", "question").get("value").asDouble()).isGreaterThanOrEqualTo(0.75);
    }

    @Test
    void evidenceIsFoundAgainWhenTheDocumentMovedAndAQuestionAboutAMissingDocumentIsReportedNotGuessed() throws Exception {
        Account source = withDocs("");
        String file = export(source, finalTestSet(source, "moving", 8));

        Account shifted = withDocs("A preface paragraph that was added later and moves every passage down the page.\n\n");
        JsonNode moved = json.readTree(importInto(shifted, file, "").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(moved.get("skipped")).as("exact text, shifted: found").isEmpty();
        assertThat(moved.get("testset").get("status").asText()).as("not finalized unless asked").isEqualTo("DRAFT");

        Account partial = register();
        uploadOk(partial, "Doc0.md", DOCS.get(0).getBytes(StandardCharsets.UTF_8));
        drainQueue();
        JsonNode some = json.readTree(importInto(partial, file, "").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        int total = file.split("\n").length - 1;
        assertThat(some.get("imported").asInt() + some.get("skipped").size()).isEqualTo(total);
        if (some.get("skipped").size() > 0) {
            assertThat(some.get("skipped").get(0).get("reason").asText()).contains("no document titled");
            assertThat(some.get("skipped").get(0).get("line").asInt()).isGreaterThan(1);
        }
        Account none = register();
        importInto(none, file, "").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("None of the")));
        assertThat(count(none, "SELECT count(*) FROM test_questions")).isZero();
    }

    @Test
    void badFilesNamesRolesAndOtherWorkspacesAreHandled() throws Exception {
        Account source = withDocs("");
        String set = finalTestSet(source, "rules", 6);
        String file = export(source, set);

        importInto(source, file, "").andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("already exists")));
        importInto(source, file, "?name=rules%20copy").andExpect(status().isCreated());
        importInto(source, "   ", "?name=empty").andExpect(status().isBadRequest());
        importInto(source, "{\"format\":\"glassbox-testset\",\"version\":1}\nnot json at all", "?name=garbage").andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Line 2")));
        importInto(source, "{\"format\":\"something-else\",\"version\":1}", "?name=other").andExpect(status().isBadRequest());
        importInto(source, "{\"format\":\"glassbox-testset\",\"version\":1,\"name\":\"only a header\"}", "").andExpect(status().isBadRequest());
        importInto(source, "{\"question\":\"q\",\"expectedAnswer\":\"a\",\"qtype\":\"TELEPATHY\",\"evidence\":[]}", "?name=type").andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/testsets/import?name=ct").header("Authorization", "Bearer " + source.token()).header("X-Workspace-Id", source.workspace())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnsupportedMediaType());

        String readKey = ok(send(post("/api/workspaces/" + source.workspace() + "/api-keys"), source, "{\"label\":\"ro\",\"scopes\":[\"read\"]}")).get("key").asText();
        mvc.perform(get("/api/testsets/" + set + "/export").header("X-API-Key", readKey)).andExpect(status().isOk());
        mvc.perform(post("/api/testsets/import?name=viewer").header("X-API-Key", readKey).contentType("application/x-ndjson").content(file)).andExpect(status().isForbidden());

        Account other = register();
        send(get("/api/testsets/" + set + "/export"), other, null).andExpect(status().isNotFound());
    }
}
