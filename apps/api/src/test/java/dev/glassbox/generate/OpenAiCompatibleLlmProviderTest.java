package dev.glassbox.generate;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Contract tests with recorded-style fixtures. The real Ollama is never called in the build (CLAUDE.md). */
@WireMockTest
class OpenAiCompatibleLlmProviderTest {
    private static final LlmProvider.Request REQUEST = new LlmProvider.Request(
            List.of(LlmProvider.Message.system("be brief"), LlmProvider.Message.user("hi")), 0.0, 123);

    private OpenAiCompatibleLlmProvider provider(WireMockRuntimeInfo wm, String apiKey, int attempts) {
        return new OpenAiCompatibleLlmProvider(new ObjectMapper(), wm.getHttpBaseUrl(), "test-model", apiKey, attempts,
                Duration.ofMillis(5), Duration.ofSeconds(10));
    }

    private static String sse(String... dataLines) {
        StringBuilder sb = new StringBuilder();
        for (String d : dataLines) sb.append("data: ").append(d).append("\n\n");
        return sb.toString();
    }

    private static String chunk(String content) {
        return "{\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":" + new ObjectMapper().valueToTree(content) + "}}]}";
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder stream(String body) {
        return aResponse().withHeader("Content-Type", "text/event-stream").withBody(body);
    }

    @Test
    void streamsTokensInOrderSkippingEmptyDeltasAndStoppingAtDone(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/chat/completions").willReturn(stream(sse(
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
                chunk("Pumps "), chunk("are "), chunk("inspected [c1]."),
                "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
                "[DONE]",
                chunk("never delivered")))));

        List<String> tokens = new ArrayList<>();
        provider(wm, "", 1).stream(REQUEST, tokens::add);

        assertThat(tokens).containsExactly("Pumps ", "are ", "inspected [c1].");
    }

    @Test
    void sendsModelMessagesStreamFlagAndLimits(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/chat/completions").willReturn(stream(sse(chunk("ok"), "[DONE]"))));
        provider(wm, "", 1).stream(REQUEST, t -> { });
        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(equalToJson("{\"model\":\"test-model\",\"stream\":true,\"temperature\":0.0,\"max_tokens\":123,"
                        + "\"messages\":[{\"role\":\"system\",\"content\":\"be brief\"},{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .withoutHeader("Authorization"));
    }

    @Test
    void jsonModeAndSeedAreSentOnlyWhenAskedForAndCompleteJoinsTheTokens(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/chat/completions").willReturn(stream(sse(chunk("{\"question\": "), chunk("\"Why?\"}"), "[DONE]"))));
        LlmProvider.Request json = new LlmProvider.Request(REQUEST.messages(), 0.2, 50, true, 7);
        assertThat(provider(wm, "", 1).complete(json)).isEqualTo("{\"question\": \"Why?\"}");
        verify(postRequestedFor(urlEqualTo("/v1/chat/completions")).withRequestBody(equalToJson("{\"model\":\"test-model\",\"stream\":true,\"temperature\":0.2,"
                + "\"max_tokens\":50,\"response_format\":{\"type\":\"json_object\"},\"seed\":7,"
                + "\"messages\":[{\"role\":\"system\",\"content\":\"be brief\"},{\"role\":\"user\",\"content\":\"hi\"}]}")));
    }

    @Test
    void sendsABearerKeyOnlyWhenConfigured(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/chat/completions").willReturn(stream(sse(chunk("ok"), "[DONE]"))));
        provider(wm, "sk-secret", 1).stream(REQUEST, t -> { });
        verify(postRequestedFor(urlEqualTo("/v1/chat/completions")).withHeader("Authorization", equalTo("Bearer sk-secret")));
    }

    @Test
    void retriesTransientFailuresBeforeTheFirstToken(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/chat/completions").inScenario("s").whenScenarioStateIs("Started").willReturn(serviceUnavailable()).willSetStateTo("up"));
        stubFor(post("/v1/chat/completions").inScenario("s").whenScenarioStateIs("up").willReturn(stream(sse(chunk("recovered"), "[DONE]"))));
        List<String> tokens = new ArrayList<>();
        provider(wm, "", 3).stream(REQUEST, tokens::add);
        assertThat(tokens).containsExactly("recovered");
        verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void clientErrorsAreNotRetriedAndCarryTheServersMessage(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(404).withBody("{\"error\":\"model 'x' not found\"}")));
        assertThatThrownBy(() -> provider(wm, "", 4).stream(REQUEST, t -> { }))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTP 404").hasMessageContaining("not found");
        verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void aFailureAfterTokensWereDeliveredIsNotRetriedSoTextIsNeverDuplicated(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/chat/completions").willReturn(stream(sse(chunk("partial "), "{\"error\":{\"message\":\"model crashed\"}}"))));
        List<String> tokens = new ArrayList<>();
        assertThatThrownBy(() -> provider(wm, "", 4).stream(REQUEST, tokens::add))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("model crashed");
        assertThat(tokens).containsExactly("partial ");
        verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void cancellationFromTheCallbackPropagatesAndStopsReading(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/chat/completions").willReturn(stream(sse(chunk("one "), chunk("two "), chunk("three "), "[DONE]"))));
        List<String> tokens = new ArrayList<>();
        assertThatThrownBy(() -> provider(wm, "", 3).stream(REQUEST, t -> {
            tokens.add(t);
            if (tokens.size() == 2) throw new LlmProvider.Cancelled();
        })).isInstanceOf(LlmProvider.Cancelled.class);
        assertThat(tokens).containsExactly("one ", "two ");
        verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void acceptsAPlainJsonCompletionFromServersThatIgnoreStreaming(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/chat/completions").willReturn(okJson("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"whole answer\"}}]}")));
        List<String> tokens = new ArrayList<>();
        provider(wm, "", 1).stream(REQUEST, tokens::add);
        assertThat(tokens).containsExactly("whole answer");
    }
}
