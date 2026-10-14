package dev.glassbox.embed;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * OpenAI text-embedding-3-small. Off unless an API key is configured. The key comes from the environment
 * for now; per-workspace bring-your-own keys arrive with the provider-key service.
 */
@Component
@ConditionalOnExpression("'${glassbox.embed.openai.api-key:}' != ''")
public class OpenAiEmbeddingProvider implements EmbeddingProvider {
    public static final String MODEL = "text-embedding-3-small";
    public static final int DIM = 1536;

    record Request(String model, List<String> input) {}
    record Item(int index, List<Double> embedding) {}
    record Response(List<Item> data) {}

    private final RestClient http;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration backoff;

    public OpenAiEmbeddingProvider(@Value("${glassbox.embed.openai.base-url}") String baseUrl,
                                   @Value("${glassbox.embed.openai.api-key}") String apiKey,
                                   @Value("${glassbox.embed.openai.batch-size:96}") int batchSize,
                                   @Value("${glassbox.embed.max-attempts}") int maxAttempts,
                                   @Value("${glassbox.embed.backoff}") Duration backoff) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    @Override public String modelId() { return MODEL; }

    @Override public int dimension() { return DIM; }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += batchSize) {
            List<String> batch = texts.subList(i, Math.min(texts.size(), i + batchSize));
            Response r = Retry.call("openai /v1/embeddings", maxAttempts, backoff, () -> http.post().uri("/v1/embeddings")
                    .body(new Request(MODEL, batch)).retrieve().body(Response.class));
            if (r == null || r.data() == null || r.data().size() != batch.size()) {
                throw new IllegalStateException("OpenAI returned a wrong number of embeddings");
            }
            // The API returns items with an index; do not rely on their order.
            for (Item item : r.data().stream().sorted(Comparator.comparingInt(Item::index)).toList()) {
                if (item.embedding().size() != DIM) throw new IllegalStateException("unexpected embedding dimension " + item.embedding().size());
                float[] f = new float[DIM];
                for (int k = 0; k < DIM; k++) f[k] = item.embedding().get(k).floatValue();
                out.add(f);
            }
        }
        return out;
    }
}
