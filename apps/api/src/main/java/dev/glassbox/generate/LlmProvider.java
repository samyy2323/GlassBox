package dev.glassbox.generate;

import java.util.List;
import java.util.function.Consumer;

/** Text generation behind one interface so Ollama, hosted APIs and test fakes are interchangeable (SPEC 3). */
public interface LlmProvider {
    record Message(String role, String content) {
        public static Message system(String c) { return new Message("system", c); }
        public static Message user(String c) { return new Message("user", c); }
    }

    record Request(List<Message> messages, double temperature, int maxTokens) {}

    /** Thrown from a token callback to stop generation early (client went away). Never retried. */
    final class Cancelled extends RuntimeException {
        public Cancelled() {
            super("generation cancelled", null, false, false);
        }
    }

    String model();

    /** Blocks until the answer is complete, calling {@code onToken} for each piece as it arrives. */
    void stream(Request request, Consumer<String> onToken);
}
