package dev.glassbox.rerank;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.glassbox.embed.SidecarClient;
import dev.glassbox.retrieval.Candidate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@WireMockTest
class SidecarRerankerTest {
    private SidecarCrossEncoderReranker reranker(WireMockRuntimeInfo wm) {
        return new SidecarCrossEncoderReranker(new SidecarClient(wm.getHttpBaseUrl(), 3, Duration.ofMillis(5)));
    }

    private static Candidate cand(String text) {
        return new Candidate(UUID.randomUUID(), UUID.randomUUID(), text, 10, 0, 10, 1, 0.0, null, null, null, null, null);
    }

    @Test
    void sendsQueryAndTextsThenSortsByScoreAndTruncates(WireMockRuntimeInfo wm) {
        stubFor(post("/rerank").willReturn(okJson("{\"model\":\"ms-marco-MiniLM-L-6-v2\",\"scores\":[-3.5, 9.1, 0.2]}")));
        List<Candidate> in = List.of(cand("low"), cand("high"), cand("mid"));

        List<Candidate> out = reranker(wm).rerank("how often", in, 2);

        assertThat(out).extracting(Candidate::text).containsExactly("high", "mid");
        assertThat(out).extracting(Candidate::rerankScore).containsExactly(9.1, 0.2);
        verify(postRequestedFor(urlEqualTo("/rerank")).withRequestBody(equalToJson(
                "{\"query\":\"how often\",\"documents\":[\"low\",\"high\",\"mid\"],\"model\":\"ms-marco-MiniLM-L-6-v2\"}")));
    }

    @Test
    void equalScoresKeepTheFusedOrder(WireMockRuntimeInfo wm) {
        stubFor(post("/rerank").willReturn(okJson("{\"model\":\"m\",\"scores\":[1.0, 1.0, 1.0]}")));
        List<Candidate> in = List.of(cand("first"), cand("second"), cand("third"));
        assertThat(reranker(wm).rerank("q", in, 3)).extracting(Candidate::text).containsExactly("first", "second", "third");
    }

    @Test
    void largePoolsAreSentInBatches(WireMockRuntimeInfo wm) {
        stubFor(post("/rerank").willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("placeholder")));
        // Respond with as many scores as documents in each call.
        stubFor(post("/rerank").withRequestBody(matchingJsonPath("$.documents[127]")).atPriority(1)
                .willReturn(okJson("{\"model\":\"m\",\"scores\":[" + String.join(",", java.util.Collections.nCopies(128, "1.0")) + "]}")));
        stubFor(post("/rerank").withRequestBody(notMatching(".*\"doc127\".*")).atPriority(2)
                .willReturn(okJson("{\"model\":\"m\",\"scores\":[2.0, 2.0]}")));
        List<Candidate> in = new ArrayList<>();
        for (int i = 0; i < 130; i++) in.add(cand("doc" + i));

        List<Candidate> out = reranker(wm).rerank("q", in, 130);

        assertThat(out).hasSize(130);
        verify(2, postRequestedFor(urlEqualTo("/rerank")));
    }

    @Test
    void retriesTransientFailuresButRejectsWrongScoreCounts(WireMockRuntimeInfo wm) {
        stubFor(post("/rerank").inScenario("r").whenScenarioStateIs("Started").willReturn(serviceUnavailable()).willSetStateTo("up"));
        stubFor(post("/rerank").inScenario("r").whenScenarioStateIs("up").willReturn(okJson("{\"model\":\"m\",\"scores\":[1.0]}")));
        assertThat(reranker(wm).rerank("q", List.of(cand("a")), 1)).hasSize(1);

        stubFor(post("/rerank").willReturn(okJson("{\"model\":\"m\",\"scores\":[1.0]}")));
        assertThatThrownBy(() -> reranker(wm).rerank("q", List.of(cand("a"), cand("b")), 2)).hasMessageContaining("wrong number");
    }

    @Test
    void emptyInputMakesNoCall(WireMockRuntimeInfo wm) {
        assertThat(reranker(wm).rerank("q", List.of(), 5)).isEmpty();
        verify(0, postRequestedFor(urlEqualTo("/rerank")));
    }

    @Test
    void noRerankerJustTruncates() {
        List<Candidate> in = List.of(cand("a"), cand("b"), cand("c"));
        assertThat(new NoReranker().rerank("q", in, 2)).extracting(Candidate::text).containsExactly("a", "b");
        assertThat(new NoReranker().rerank("q", in, 10)).hasSize(3);
    }
}
