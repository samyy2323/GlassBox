package dev.glassbox.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.glassbox.ingest.ApiTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Sign-in is limited per client address. Own class: it needs its own context, and no test here may call register() from the shared address. */
@TestPropertySource(properties = "glassbox.rate.auth-per-minute=3")
class RateLimitAuthTest extends ApiTestSupport {
    private static RequestPostProcessor from(String address) {
        return r -> {
            r.setRemoteAddr(address);
            return r;
        };
    }

    @Test
    void signInIsLimitedPerClientAddressAndAnotherAddressIsNotAffected() throws Exception {
        String body = "{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}";
        for (int i = 0; i < 3; i++) {
            assertThat(code(post("/api/auth/login").with(from("203.0.113.9")).contentType(MediaType.APPLICATION_JSON).content(body))).as("attempt %d", i + 1).isEqualTo(401);
        }
        mvc.perform(post("/api/auth/login").with(from("203.0.113.9")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "20"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("sign-in")));
        assertThat(code(post("/api/auth/login").with(from("198.51.100.4")).contentType(MediaType.APPLICATION_JSON).content(body))).isEqualTo(401);
    }

    private int code(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b).andReturn().getResponse().getStatus();
    }
}
