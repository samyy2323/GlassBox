package dev.glassbox.generate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.embed.Retry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Streams from any OpenAI-compatible {@code /v1/chat/completions} endpoint: Ollama (default), Groq, OpenRouter,
 * Gemini's compatible API, vLLM. Which one is purely configuration (glassbox.llm.*).
 *
 * Transient failures are retried only before the first token has been delivered; after that a retry would repeat text
 * the caller already forwarded, so the error is surfaced instead.
 */
@Component
public class OpenAiCompatibleLlmProvider implements LlmProvider {
    record Msg(String role, String content) {}
    record Body(String model, List<Msg> messages, boolean stream, double temperature, @JsonProperty("max_tokens") int maxTokens) {}

    private final RestClient http;
    private final ObjectMapper json;
    private final String model;
    private final int maxAttempts;
    private final Duration backoff;

    public OpenAiCompatibleLlmProvider(ObjectMapper json,
                                       @Value("${glassbox.llm.base-url}") String baseUrl,
                                       @Value("${glassbox.llm.model}") String model,
                                       @Value("${glassbox.llm.api-key:}") String apiKey,
                                       @Value("${glassbox.llm.max-attempts}") int maxAttempts,
                                       @Value("${glassbox.llm.backoff}") Duration backoff,
                                       @Value("${glassbox.llm.read-timeout}") Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(readTimeout);
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl).requestFactory(factory);
        if (apiKey != null && !apiKey.isBlank()) b.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        this.http = b.build();
        this.json = json;
        this.model = model;
        this.maxAttempts = maxAttempts;
        this.backoff = backoff;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void stream(Request request, Consumer<String> onToken) {
        AtomicBoolean delivered = new AtomicBoolean();
        Consumer<String> tracked = t -> {
            delivered.set(true);
            onToken.accept(t);
        };
        Body body = new Body(model, request.messages().stream().map(m -> new Msg(m.role(), m.content())).toList(),
                true, request.temperature(), request.maxTokens());
        try {
            Retry.call("llm chat", maxAttempts, backoff, () -> {
                try {
                    return readStream(body, tracked);
                } catch (RuntimeException e) {
                    // Once text was forwarded a retry would duplicate it: wrap so Retry does not retry.
                    if (delivered.get() && !(e instanceof Cancelled)) throw new IllegalStateException("generation failed mid-stream: " + e.getMessage(), e);
                    throw e;
                }
            });
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("LLM endpoint returned HTTP " + e.getStatusCode().value() + ": " + abbreviate(e.getResponseBodyAsString()), e);
        }
    }

    private Object readStream(Body body, Consumer<String> onToken) {
        return http.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON).body(body)
                .exchange((req, res) -> {
                    if (res.getStatusCode().isError()) {
                        byte[] err = res.getBody().readAllBytes();
                        throw new RestClientResponseException("LLM error", res.getStatusCode().value(), res.getStatusText(),
                                res.getHeaders(), err, StandardCharsets.UTF_8);
                    }
                    MediaType type = res.getHeaders().getContentType();
                    if (type != null && type.isCompatibleWith(MediaType.APPLICATION_JSON)) { // server ignored stream=true
                        try {
                            String token = token(json.readTree(res.getBody()));
                            if (token != null && !token.isEmpty()) onToken.accept(token);
                        } catch (IOException e) {
                            throw new IllegalStateException("could not read the LLM response", e);
                        }
                        return null;
                    }
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(res.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).strip();
                            if (data.equals("[DONE]")) break;
                            if (data.isEmpty()) continue;
                            String token = token(json.readTree(data));
                            if (token != null && !token.isEmpty()) onToken.accept(token);
                        }
                    } catch (IOException e) {
                        throw new IllegalStateException("could not read the LLM stream", e);
                    }
                    return null;
                });
    }

    /** choices[0].delta.content for streamed chunks; surfaces an in-band {"error": ...} object. */
    static String token(JsonNode event) {
        if (event.hasNonNull("error")) {
            JsonNode err = event.get("error");
            throw new IllegalStateException("LLM error: " + (err.hasNonNull("message") ? err.get("message").asText() : err.asText()));
        }
        JsonNode choice = event.path("choices").path(0);
        JsonNode content = choice.path("delta").path("content");
        if (content.isTextual()) return content.asText();
        content = choice.path("message").path("content"); // servers that ignore stream=true
        return content.isTextual() ? content.asText() : null;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
