package dev.glassbox.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/** Reads the model's reply tolerantly: code fences, a sentence before the object, alternative key names. */
public final class QuestionJson {
    public record Parsed(String question, String expectedAnswer) {}

    private QuestionJson() {}

    public static Optional<Parsed> parse(ObjectMapper json, String raw) {
        if (raw == null) return Optional.empty();
        String text = raw.strip();
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) return Optional.empty();
        JsonNode node;
        try {
            node = json.readTree(text.substring(open, close + 1));
        } catch (Exception e) {
            return Optional.empty();
        }
        String q = first(node, "question", "Question", "q");
        String a = first(node, "expected_answer", "expectedAnswer", "answer", "Answer", "a");
        if (q == null || a == null) return Optional.empty();
        q = q.strip();
        a = a.strip();
        if (q.length() < 8 || q.length() > 600 || a.isEmpty() || a.length() > 1200 || q.equalsIgnoreCase(a)) return Optional.empty();
        return Optional.of(new Parsed(q, a));
    }

    private static String first(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && v.isValueNode() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }
}
