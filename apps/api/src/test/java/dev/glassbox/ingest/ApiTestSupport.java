package dev.glassbox.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.TestDb;
import dev.glassbox.TestFiles;
import dev.glassbox.jobs.JobWorker;
import dev.glassbox.tenancy.Tx;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
abstract class ApiTestSupport {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Tx tx;
    @Autowired JobWorker worker;

    record Account(String token, UUID workspace, String email) {}

    @BeforeEach
    void clean() {
        tx.worker(j -> j.update("DELETE FROM jobs"));
    }

    Account register() throws Exception {
        String email = "doc-" + UUID.randomUUID() + "@example.com";
        JsonNode body = json.readTree(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String token = body.get("accessToken").asText();
        JsonNode ws = json.readTree(mvc.perform(get("/api/workspaces").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        return new Account(token, UUID.fromString(ws.get(0).get("id").asText()), email);
    }

    ResultActions upload(Account a, String name, byte[] data) throws Exception {
        return mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", name, null, data))
                .header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace()));
    }

    JsonNode uploadOk(Account a, String name, byte[] data) throws Exception {
        return json.readTree(upload(a, name, data).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("document");
    }

    int countRows(Account a, String table) {
        Integer n = tx.workspace(a.workspace(), null, j -> j.queryForObject("SELECT count(*) FROM " + table, Integer.class));
        return n;
    }

    int countJobs(String where) {
        Integer n = tx.worker(j -> j.queryForObject("SELECT count(*) FROM jobs " + where, Integer.class));
        return n;
    }

    void drainQueue() {
        int guard = 0;
        while (worker.runOnce("test-worker") && guard++ < 100) { /* keep going */ }
    }

    JsonNode doc(Account a, String id) throws Exception {
        JsonNode page = json.readTree(mvc.perform(get("/api/documents").header("Authorization", "Bearer " + a.token())
                        .header("X-Workspace-Id", a.workspace())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode d : page.get("items")) if (d.get("id").asText().equals(id)) return d;
        throw new AssertionError("document not listed: " + id);
    }

    String pdfText() {
        return "Quarterly maintenance policy " + UUID.randomUUID() + ".\n\nAll pumps must be inspected every 90 days.";
    }

}
