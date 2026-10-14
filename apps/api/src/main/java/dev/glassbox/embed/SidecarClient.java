package dev.glassbox.embed;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for the ML sidecar (apps/ml-sidecar). Shared by embedding and, later, reranking. */
@Component
public class SidecarClient {
    public record EmbedRequest(List<String> texts, String model) {}
    public record EmbedResponse(String model, int dim, List<List<Double>> embeddings) {}
    public record RerankRequest(String query, List<String> documents, String model) {}
    public record RerankResponse(String model, List<Double> scores) {}
    public record NliPairDto(String premise, String hypothesis) {}
    public record NliRequest(List<NliPairDto> pairs, String model) {}
    public record NliScoreDto(double entailment, double neutral, double contradiction) {}
    public record NliResponse(String model, List<NliScoreDto> results) {}

    private final RestClient http;
    private final int maxAttempts;
    private final Duration backoff;

    public SidecarClient(@Value("${glassbox.embed.sidecar.url}") String url,
                         @Value("${glassbox.embed.max-attempts}") int maxAttempts,
                         @Value("${glassbox.embed.backoff}") Duration backoff) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.http = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    public EmbedResponse embed(String model, List<String> texts) {
        return Retry.call("sidecar /embed", maxAttempts, backoff, () -> http.post().uri("/embed")
                .body(new EmbedRequest(texts, model)).retrieve().body(EmbedResponse.class));
    }

    public RerankResponse rerank(String model, String query, List<String> documents) {
        return Retry.call("sidecar /rerank", maxAttempts, backoff, () -> http.post().uri("/rerank")
                .body(new RerankRequest(query, documents, model)).retrieve().body(RerankResponse.class));
    }

    public NliResponse nli(String model, List<NliPairDto> pairs) {
        return Retry.call("sidecar /nli", maxAttempts, backoff, () -> http.post().uri("/nli")
                .body(new NliRequest(pairs, model)).retrieve().body(NliResponse.class));
    }
}
