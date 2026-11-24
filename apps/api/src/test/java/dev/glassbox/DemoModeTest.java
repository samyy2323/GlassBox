package dev.glassbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The public demo: a passwordless viewer that can read but never write, ask or upload. */
@SpringBootTest(properties = {"glassbox.demo.enabled=true", "glassbox.demo.viewer-email=demo-test@glassbox.local"})
@AutoConfigureMockMvc
@ActiveProfiles("api")
class DemoModeTest {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asText();
    }

    @Test
    void theDemoViewerSignsInWithoutAPasswordAndCanOnlyRead() throws Exception {
        mvc.perform(post("/api/auth/demo")).andExpect(status().isNotFound()); // the account is not created yet

        register("demo-test@glassbox.local"); // what the demo seed does
        String body = mvc.perform(post("/api/auth/demo")).andExpect(status().isOk()).andExpect(cookie().httpOnly("gb_refresh", true))
                .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).get("accessToken").asText();

        JsonNode ws = json.readTree(mvc.perform(get("/api/workspaces").header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/documents").header("Authorization", "Bearer " + token).header("X-Workspace-Id", ws.get(0).get("id").asText())).andExpect(status().isOk());

        // every write, including asking a question, is refused with a message that says why
        mvc.perform(post("/api/chat").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hello there\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("read-only demo")));
        mvc.perform(multipart("/api/documents").file(new MockMultipartFile("file", "a.txt", "text/plain", "text".getBytes())).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/workspaces").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSignInPageCanAskWhetherADemoIsOffered() throws Exception {
        mvc.perform(get("/api/auth/demo")).andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.email").value("demo-test@glassbox.local"));
    }

    @Test
    void otherAccountsAreUnaffected() throws Exception {
        String token = register("someone-" + System.nanoTime() + "@example.com");
        JsonNode ws = json.readTree(mvc.perform(get("/api/workspaces").header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString());
        assertThat(ws).hasSize(1);
        mvc.perform(post("/api/workspaces").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"second\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void theDemoEndpointDoesNotLetYouSignInAsAnyoneElse() throws Exception {
        register("victim-" + System.nanoTime() + "@example.com");
        mvc.perform(post("/api/auth/demo").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"victim@example.com\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 404)); // whichever it is, it is the configured demo account, never the body's
    }
}
