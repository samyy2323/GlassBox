package dev.glassbox.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.ingest.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** The API-wide request limits, with tiny allowances so the real filter chain refuses within a few calls. */
@TestPropertySource(properties = {"glassbox.rate.principal-per-minute=8", "glassbox.rate.workspace-per-minute=12",
        "glassbox.rate.public-per-minute=4", "glassbox.rate.anonymous-per-minute=3"})
class RateLimitApiTest extends ApiTestSupport {
    @org.springframework.beans.factory.annotation.Autowired io.micrometer.core.instrument.MeterRegistry registry;

    private static RequestPostProcessor from(String address) {
        return r -> {
            r.setRemoteAddr(address);
            return r;
        };
    }

    private int code(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }

    @Test
    void aSignedInUserHasAnAllowanceOfTheirOwnAndOthersAreNotAffected() throws Exception {
        Account a = register(), b = register();
        int served = 0;
        while (served < 20 && code(get("/api/workspaces").header("Authorization", "Bearer " + a.token()).with(from("192.0.2.1"))) == 200) served++;
        assertThat(served).as("register() has already spent a request or two of the 8").isBetween(5, 8);
        mvc.perform(get("/api/workspaces").header("Authorization", "Bearer " + a.token()).with(from("192.0.2.1")))
                .andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertThat(code(get("/api/workspaces").header("Authorization", "Bearer " + b.token()).with(from("192.0.2.1")))).as("another user from the same address").isEqualTo(200);
    }

    @Test
    void anotherWorkspacesHeaderCannotUseUpAWorkspacesAllowance() throws Exception {
        Account victim = register(), attacker = register();
        // the attacker names the victim's workspace on every request; that header is not trusted for the limit, so only the attacker's own allowance is spent
        int refused = 0;
        for (int i = 0; i < 14; i++) {
            int s = code(get("/api/documents").header("Authorization", "Bearer " + attacker.token()).header("X-Workspace-Id", victim.workspace()).with(from("192.0.2.77")));
            if (s == 429) refused++;
        }
        assertThat(refused).isGreaterThan(0);
        assertThat(code(get("/api/documents").header("Authorization", "Bearer " + victim.token()).header("X-Workspace-Id", victim.workspace()).with(from("192.0.2.88"))))
                .as("the victim is untouched").isEqualTo(200);
    }

    @Test
    void anApiKeyIsLimitedPerKeyAndPerWorkspaceTogether() throws Exception {
        Account a = register();
        String k1 = createKey(a), k2 = createKey(a);
        // each key may make 8 requests, the workspace 12 across both: 7 + 7 is refused at 12 although neither key is spent
        int ok = 0, refusedKeyAlone = 0;
        for (int i = 0; i < 7; i++) if (code(get("/api/documents").header("X-API-Key", k1).with(from("192.0.2.5"))) == 200) ok++;
        for (int i = 0; i < 7; i++) if (code(get("/api/documents").header("X-API-Key", k2).with(from("192.0.2.5"))) == 200) ok++;
        assertThat(ok).as("the workspace bucket cut it at 12").isEqualTo(12);
        Account other = register();
        assertThat(code(get("/api/documents").header("X-API-Key", createKey(other)).with(from("192.0.2.5")))).as("another workspace's key").isEqualTo(200);
        assertThat(refusedKeyAlone).isZero();
    }

    @Test
    void requestsWithoutACredentialAreBoundedToo() throws Exception {
        for (int i = 0; i < 3; i++) assertThat(code(get("/api/documents").with(from("198.51.100.200")))).isEqualTo(401);
        assertThat(code(get("/api/documents").with(from("198.51.100.200")))).isEqualTo(429);
    }

    @Test
    void publicReportLinksAreLimitedPerAddressAndTheRefusalCountsInTheMetric() throws Exception {
        for (int i = 0; i < 4; i++) assertThat(code(get("/api/public/reports/not-a-real-token").with(from("203.0.113.50")))).isEqualTo(404);
        assertThat(code(get("/api/public/reports/not-a-real-token").with(from("203.0.113.50")))).isEqualTo(429);
        assertThat(registry.find("rate_limited_total").tag("scope", "share_link").counter().count()).isGreaterThanOrEqualTo(1.0);
    }

    private String createKey(Account a) throws Exception {
        String raw = mvc.perform(post("/api/workspaces/" + a.workspace() + "/api-keys").header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"ci\",\"scopes\":[\"read\"]}")).andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(raw);
        return n.has("key") ? n.get("key").asText() : n.get("apiKey").asText();
    }
}
