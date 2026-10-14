package dev.glassbox.generate;

import java.util.List;
import java.util.function.Consumer;

/** Text generation behind one interface so Ollama, hosted APIs and test fakes are interchangeable (SPEC 3). */
public interface LlmProvider {
    record Message(String role, String content) {
        public static Message system(String c) { return new Message("system", c); }
        public static Message user(String c) { return new Message("user", c); }
    }

    /** {@code jsonMode} asks the server for a JSON object; {@code seed} (nullable) makes sampling repeatable where supported. */
    record Request(List<Message> messages, double temperature, int maxTokens, boolean jsonMode, Integer seed) {
        public Request(List<Message> messages, double temperature, int maxTokens) {
            this(messages, temperature, maxTokens, false, null);
        }
    }

    /** Thrown from a token callback to stop generation early (client went away). Never retried. */
    final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("generation cancelled", null, false, false);
        }
    }

    String model();

    /** Blocks until the answer is complete, calling {@code onToken} for each piece as it arrives. */
    void stream(Request request, Consumer<String> onToken);

    /** The whole answer as one string, for callers that do not show partial text (test set generation, judges). */
    default String complete(Request request) {
        StringBuilder out = new StringBuilder();
        stream(request, out::append);
        return out.toString();
    }
}
