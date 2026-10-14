package dev.glassbox.embed;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

/** Provider contract tests against recorded-style fixtures; no live API is ever called (CLAUDE.md). */
@WireMockTest
class ProvidersWireMockTest {

    private static String vec(int dim, double first) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < dim; i++) parts.add(i == 0 ? String.valueOf(first) : "0.0");
        return "[" + String.join(",", parts) + "]";
    }

    private static String sidecarBody(int n, int dim) {
        List<String> vs = new ArrayList<>();
        for (int i = 0; i < n; i++) vs.add(vec(dim, i + 1));
        return "{\"model\":\"bge-small-en-v1.5\",\"dim\":" + dim + ",\"embeddings\":[" + String.join(",", vs) + "]}";
    }

    private SidecarEmbeddingProvider sidecar(WireMockRuntimeInfo wm, int batch, int attempts) {
        return new SidecarEmbeddingProvider(new SidecarClient(wm.getHttpBaseUrl(), attempts, Duration.ofMillis(5)), batch);
    }

    private OpenAiEmbeddingProvider openai(WireMockRuntimeInfo wm, int batch, int attempts) {
        return new OpenAiEmbeddingProvider(wm.getHttpBaseUrl(), "sk-test-key", batch, attempts, Duration.ofMillis(5));
    }

    // ------------------------------------------------------------ sidecar

    @Test
    void sidecarSendsModelAndTextsAndParsesVectors(WireMockRuntimeInfo wm) {
        stubFor(post("/embed").willReturn(okJson(sidecarBody(2, 384))));
        List<float[]> out = sidecar(wm, 64, 1).embed(List.of("alpha", "beta"));
        assertThat(out).hasSize(2);
        assertThat(out.get(0)).hasSize(384);
        assertThat(out.get(1)[0]).isEqualTo(2f);
        verify(postRequestedFor(urlEqualTo("/embed"))
                .withRequestBody(equalToJson("{\"texts\":[\"alpha\",\"beta\"],\"model\":\"bge-small-en-v1.5\"}")));
    }

    @Test
    void sidecarSplitsLargeInputsIntoBatchesAndKeepsOrder(WireMockRuntimeInfo wm) {
        stubFor(post("/embed").inScenario("b").whenScenarioStateIs("Started").willReturn(okJson(sidecarBody(2, 384))).willSetStateTo("second"));
        stubFor(post("/embed").inScenario("b").whenScenarioStateIs("second").willReturn(okJson(sidecarBody(1, 384))));
        List<float[]> out = sidecar(wm, 2, 1).embed(List.of("a", "b", "c"));
        assertThat(out).hasSize(3);
        verify(2, postRequestedFor(urlEqualTo("/embed")));
    }

    @Test
    void sidecarRetriesTransientServerErrorsWithBackoff(WireMockRuntimeInfo wm) {
        stubFor(post("/embed").inScenario("r").whenScenarioStateIs("Started").willReturn(serviceUnavailable()).willSetStateTo("one"));
        stubFor(post("/embed").inScenario("r").whenScenarioStateIs("one").willReturn(status(502)).willSetStateTo("two"));
        stubFor(post("/embed").inScenario("r").whenScenarioStateIs("two").willReturn(okJson(sidecarBody(1, 384))));
        assertThat(sidecar(wm, 64, 4).embed(List.of("x"))).hasSize(1);
        verify(3, postRequestedFor(urlEqualTo("/embed")));
    }

    @Test
    void sidecarGivesUpAfterMaxAttempts(WireMockRuntimeInfo wm) {
        stubFor(post("/embed").willReturn(serviceUnavailable()));
        assertThatThrownBy(() -> sidecar(wm, 64, 3).embed(List.of("x"))).isInstanceOf(HttpServerErrorException.class);
        verify(3, postRequestedFor(urlEqualTo("/embed")));
    }

    @Test
    void sidecarDoesNotRetryClientErrors(WireMockRuntimeInfo wm) {
        stubFor(post("/embed").willReturn(status(422)));
        assertThatThrownBy(() -> sidecar(wm, 64, 4).embed(List.of("x"))).isInstanceOf(HttpClientErrorException.class);
        verify(1, postRequestedFor(urlEqualTo("/embed")));
    }

    @Test
    void sidecarRejectsWrongDimensionOrCount(WireMockRuntimeInfo wm) {
        stubFor(post("/embed").willReturn(okJson(sidecarBody(1, 768))));
        assertThatThrownBy(() -> sidecar(wm, 64, 1).embed(List.of("x"))).hasMessageContaining("dimension");
        stubFor(post("/embed").willReturn(okJson(sidecarBody(1, 384))));
        assertThatThrownBy(() -> sidecar(wm, 64, 1).embed(List.of("x", "y"))).hasMessageContaining("wrong number");
    }

    // ------------------------------------------------------------ openai

    @Test
    void openAiSendsBearerKeyAndReordersByIndex(WireMockRuntimeInfo wm) {
        // Items deliberately returned out of order.
        stubFor(post("/v1/embeddings").willReturn(okJson(
                "{\"data\":[{\"index\":1,\"embedding\":" + vec(1536, 2) + "},{\"index\":0,\"embedding\":" + vec(1536, 1) + "}]}")));
        List<float[]> out = openai(wm, 96, 1).embed(List.of("first", "second"));
        assertThat(out.get(0)[0]).isEqualTo(1f);
        assertThat(out.get(1)[0]).isEqualTo(2f);
        verify(postRequestedFor(urlEqualTo("/v1/embeddings"))
                .withHeader("Authorization", equalTo("Bearer sk-test-key"))
                .withRequestBody(equalToJson("{\"model\":\"text-embedding-3-small\",\"input\":[\"first\",\"second\"]}")));
    }

    @Test
    void openAiHonoursRetryAfterOnRateLimit(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/embeddings").inScenario("rl").whenScenarioStateIs("Started")
                .willReturn(status(429).withHeader("Retry-After", "0")).willSetStateTo("ok"));
        stubFor(post("/v1/embeddings").inScenario("rl").whenScenarioStateIs("ok")
                .willReturn(okJson("{\"data\":[{\"index\":0,\"embedding\":" + vec(1536, 1) + "}]}")));
        assertThat(openai(wm, 96, 3).embed(List.of("x"))).hasSize(1);
        verify(2, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }

    @Test
    void openAiAuthFailureIsNotRetried(WireMockRuntimeInfo wm) {
        stubFor(post("/v1/embeddings").willReturn(status(401)));
        assertThatThrownBy(() -> openai(wm, 96, 4).embed(List.of("x"))).isInstanceOf(HttpClientErrorException.class);
        verify(1, postRequestedFor(urlEqualTo("/v1/embeddings")));
    }
}
