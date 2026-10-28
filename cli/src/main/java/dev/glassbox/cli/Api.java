package dev.glassbox.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** The Glassbox HTTP API as the CLI uses it. Every failure becomes a {@link CliException} with a message that says what to do. */
public final class Api {
    private final Settings settings;
    private final HttpClient client;
    private final Duration requestTimeout;

    public Api(Settings settings, Duration requestTimeout) {
        this.settings = settings.require();
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JsonNode json(String method, String path, JsonNode body) {
        byte[] bytes = send(method, path, "application/json", body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        try {
            return Settings.JSON.readTree(bytes);
        } catch (IOException e) {
            throw new CliException("The server answered with something that is not JSON (is the address a Glassbox server?)", e);
        }
    }

    public String text(String method, String path, String contentType, String body, String accept) {
        return new String(send(method, path, contentType, body == null ? null : body.getBytes(StandardCharsets.UTF_8), accept), StandardCharsets.UTF_8);
    }

    public byte[] bytes(String method, String path, String accept) {
        return send(method, path, null, null, accept);
    }

    private byte[] send(String method, String path, String contentType, byte[] body, String accept) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(settings.server() + path)).timeout(requestTimeout).header("X-API-Key", settings.key())
                .header("Accept", accept == null ? "*/*" : accept).header("User-Agent", "glassbox-cli");
        if (contentType != null) b.header("Content-Type", contentType);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<byte[]> response;
        try {
            response = client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new CliException("The server did not answer within " + requestTimeout.toSeconds() + " seconds (" + method + " " + path + ")", e);
        } catch (IOException e) {
            throw new CliException("Could not reach " + settings.server() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException("Interrupted", e);
        }
        int code = response.statusCode();
        if (code >= 200 && code < 300) return response.body();
        throw new CliException(explain(code, response.body(), method, path));
    }

    private static String explain(int code, byte[] body, String method, String path) {
        String detail = null;
        try {
            JsonNode n = Settings.JSON.readTree(body);
            if (n != null && n.hasNonNull("detail")) detail = n.get("detail").asText();
        } catch (IOException ignored) {
            // not a problem document: fall back to the status alone
        }
        return switch (code) {
            case 401 -> "The server did not accept the API key (401). Check it, or sign in again with `glassbox login`.";
            case 403 -> "This API key may not do that (403)" + (detail == null ? "" : ": " + detail) + ". Gates need a key with the run scope.";
            case 404 -> "Not found (404)" + (detail == null ? "" : ": " + detail);
            case 429 -> "The server is limiting requests (429)" + (detail == null ? "" : ": " + detail);
            default -> "The server refused the request (" + code + ")" + (detail == null ? " at " + method + " " + path : ": " + detail);
        };
    }
}
