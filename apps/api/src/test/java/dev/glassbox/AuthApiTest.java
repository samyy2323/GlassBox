package dev.glassbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
class AuthApiTest {
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", TestDb::url);
        r.add("spring.datasource.username", () -> TestDb.APP_USER);
        r.add("spring.datasource.password", () -> TestDb.APP_PW);
        r.add("spring.flyway.url", TestDb::url);
        r.add("spring.flyway.user", () -> TestDb.OWNER_USER);
        r.add("spring.flyway.password", () -> TestDb.OWNER_PW);
        r.add("glassbox.jwt.secret", () -> "test-secret-test-secret-test-secret-123");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    record Login(String token, UUID userId, Cookie refresh) {}

    Login register(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isOk()).andExpect(cookie().httpOnly("gb_refresh", true)).andReturn();
        JsonNode body = json.readTree(r.getResponse().getContentAsString());
        return new Login(body.get("accessToken").asText(), UUID.fromString(body.get("user").get("id").asText()),
                r.getResponse().getCookie("gb_refresh"));
    }

    JsonNode get(String url, String token) throws Exception {
        return json.readTree(mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void registerCreatesPersonalWorkspaceOwnedByUser() throws Exception {
        Login a = register("owner-" + UUID.randomUUID() + "@example.com");
        JsonNode ws = get("/api/workspaces", a.token());
        assertThat(ws).hasSize(1);
        assertThat(ws.get(0).get("role").asText()).isEqualTo("OWNER");
    }

    @Test
    void duplicateEmailIsConflict() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        register(email);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email.toUpperCase() + "\",\"password\":\"correct-horse-battery\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void loginRejectsWrongPasswordAndUnknownUserIdentically() throws Exception {
        String email = "login-" + UUID.randomUUID() + "@example.com";
        register(email);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse-battery\"}")).andExpect(status().isOk());
    }

    @Test
    void apiRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/workspaces")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/workspaces").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesTokenAndDetectsReuse() throws Exception {
        Login a = register("refresh-" + UUID.randomUUID() + "@example.com");
        mvc.perform(post("/api/auth/refresh").cookie(a.refresh())).andExpect(status().isForbidden()); // no CSRF header
        MvcResult r = mvc.perform(post("/api/auth/refresh").cookie(a.refresh()).header("X-Glassbox-CSRF", "1"))
                .andExpect(status().isOk()).andReturn();
        Cookie rotated = r.getResponse().getCookie("gb_refresh");
        assertThat(rotated.getValue()).isNotEqualTo(a.refresh().getValue());
        // Replaying the old token is rejected and burns the whole family.
        mvc.perform(post("/api/auth/refresh").cookie(a.refresh()).header("X-Glassbox-CSRF", "1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").cookie(rotated).header("X-Glassbox-CSRF", "1")).andExpect(status().isUnauthorized());
    }

    @Test
    void otherUsersCannotSeeOrTouchMyWorkspace() throws Exception {
        Login a = register("a-" + UUID.randomUUID() + "@example.com");
        Login b = register("b-" + UUID.randomUUID() + "@example.com");
        String wsA = get("/api/workspaces", a.token()).get(0).get("id").asText();
        mvc.perform(get("/api/workspaces/" + wsA + "/members").header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/workspaces/" + wsA + "/api-keys").header("Authorization", "Bearer " + b.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\",\"scopes\":[\"admin\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rolesAreEnforcedAndApiKeysAreScoped() throws Exception {
        Login owner = register("owner-" + UUID.randomUUID() + "@example.com");
        String viewerEmail = "viewer-" + UUID.randomUUID() + "@example.com";
        Login viewer = register(viewerEmail);
        String ws = get("/api/workspaces", owner.token()).get(0).get("id").asText();

        mvc.perform(post("/api/workspaces/" + ws + "/members").header("Authorization", "Bearer " + owner.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + viewerEmail + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        // Viewer can read members but not create keys.
        mvc.perform(get("/api/workspaces/" + ws + "/members").header("Authorization", "Bearer " + viewer.token()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/workspaces/" + ws + "/api-keys").header("Authorization", "Bearer " + viewer.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\",\"scopes\":[\"read\"]}"))
                .andExpect(status().isForbidden());

        // Owner creates a read-only key; the CI bot can read but not administer.
        String key = json.readTree(mvc.perform(post("/api/workspaces/" + ws + "/api-keys")
                        .header("Authorization", "Bearer " + owner.token()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"ci\",\"scopes\":[\"read\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("key").asText();
        mvc.perform(get("/api/workspaces/" + ws + "/members").header("X-API-Key", key)).andExpect(status().isOk());
        mvc.perform(post("/api/workspaces/" + ws + "/api-keys").header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"x\",\"scopes\":[\"read\"]}"))
                .andExpect(status().isForbidden());
        // The key is bound to its own workspace.
        String otherWs = get("/api/workspaces", viewer.token()).get(0).get("id").asText();
        mvc.perform(get("/api/workspaces/" + otherWs + "/members").header("X-API-Key", key)).andExpect(status().isNotFound());
        mvc.perform(get("/api/workspaces").header("X-API-Key", key + "tampered")).andExpect(status().isUnauthorized());
    }

    @Test
    void lastOwnerCannotBeRemoved() throws Exception {
        Login owner = register("solo-" + UUID.randomUUID() + "@example.com");
        String ws = get("/api/workspaces", owner.token()).get(0).get("id").asText();
        mvc.perform(delete("/api/workspaces/" + ws + "/members/" + owner.userId())
                .header("Authorization", "Bearer " + owner.token())).andExpect(status().isConflict());
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
