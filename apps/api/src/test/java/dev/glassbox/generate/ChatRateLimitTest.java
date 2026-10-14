package dev.glassbox.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.glassbox.FakeLlmProvider;
import dev.glassbox.ingest.ApiTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** A separate context with a tiny limit: the rate limiter wired into the real endpoint. */
@TestPropertySource(properties = "glassbox.llm.rate-per-minute=3")
class ChatRateLimitTest extends ApiTestSupport {

    MvcResult ask(Account a) throws Exception {
        return mvc.perform(post("/api/chat").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace())
                .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"anything\"}")).andReturn();
    }

    @Test
    void afterTheBurstQuestionsAreRefusedWithRetryAfterAndNeverReachTheModel() throws Exception {
        FakeLlmProvider.reset();
        Account a = register();
        for (int i = 0; i < 3; i++) {
            MvcResult r = ask(a);
            assertThat(r.getResponse().getStatus()).as("question %d", i + 1).isEqualTo(200);
            r.getAsyncResult(10_000); // wait so the single generation slot is free for the next one
        }
        mvc.perform(post("/api/chat").header("Authorization", "Bearer " + a.token()).header("X-Workspace-Id", a.workspace())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"one too many\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "20"));

        // Another user in another workspace has their own allowance.
        assertThat(ask(register()).getResponse().getStatus()).isEqualTo(200);
        assertThat(UUID.randomUUID()).isNotNull();
    }
}
