package dev.glassbox.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.chunking.TokenIndex;
import dev.glassbox.generate.LlmProvider;
import dev.glassbox.generate.LlmProvider.Message;
import dev.glassbox.generate.LlmProvider.Request;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * LLM judge with versioned rubric prompts (SPEC 8): JSON output, temperature 0. A reply that cannot be read gives no verdict
 * (the caller leaves that metric out for the question) rather than a guess, because a made-up verdict would look like data.
 * Changing any prompt below means a new {@link #RUBRIC_VERSION}: calibration is per judge model and rubric version.
 */
@Component
public class Judge {
    public static final String RUBRIC_VERSION = "v1";
    private static final int MAX_TOKENS = 120;
    private static final int SEED = 42;

    public static final Set<String> CORRECTNESS = Set.of("correct", "partial", "wrong");
    public static final Set<String> RELEVANCE = Set.of("yes", "partly", "no");
    public static final Set<String> SUPPORT = Set.of("supported", "partial", "unsupported");

    /** What the judge said, and the (estimated) tokens it cost. */
    public record Verdict(String label, String reason, int tokensIn, int tokensOut) {}

    static final String CORRECTNESS_SYSTEM = """
            You grade answers. Compare the ANSWER with the EXPECTED answer for the QUESTION.
            correct: the answer states the same facts as the expected answer (the wording may differ).
            partial: the answer has some of the expected facts but misses or changes others.
            wrong: the answer states different facts, or says the information is not available.
            Reply with one JSON object and nothing else: {"verdict": "correct" or "partial" or "wrong", "reason": "one short sentence"}""";

    static final String RELEVANCE_SYSTEM = """
            You check whether an ANSWER addresses the QUESTION that was asked. Do not judge whether it is true.
            yes: it answers what was asked.
            partly: it touches the topic but leaves out what was asked.
            no: it is about something else, or says the information is not available.
            Reply with one JSON object and nothing else: {"verdict": "yes" or "partly" or "no", "reason": "one short sentence"}""";

    static final String SUPPORT_SYSTEM = """
            You check a STATEMENT against a TEXT.
            supported: the text says it, in any wording.
            partial: the text supports only part of the statement.
            unsupported: the text does not say it, or says something different.
            Reply with one JSON object and nothing else: {"verdict": "supported" or "partial" or "unsupported", "reason": "one short sentence"}""";

    private final LlmProvider llm;
    private final ObjectMapper json;

    public Judge(LlmProvider llm, ObjectMapper json) {
        this.llm = llm;
        this.json = json;
    }

    public String model() {
        return llm.model();
    }

    public Optional<Verdict> correctness(String question, String expected, String answer) {
        return ask(CORRECTNESS_SYSTEM, "QUESTION: " + question + "\nEXPECTED: " + expected + "\nANSWER: " + answer, CORRECTNESS);
    }

    public Optional<Verdict> relevance(String question, String answer) {
        return ask(RELEVANCE_SYSTEM, "QUESTION: " + question + "\nANSWER: " + answer, RELEVANCE);
    }

    public Optional<Verdict> support(String statement, String text) {
        return ask(SUPPORT_SYSTEM, "TEXT: " + text + "\nSTATEMENT: " + statement, SUPPORT);
    }

    private Optional<Verdict> ask(String system, String user, Set<String> allowed) {
        String raw = llm.complete(new Request(List.of(Message.system(system), Message.user(user)), 0.0, MAX_TOKENS, true, SEED));
        int in = TokenIndex.count(system) + TokenIndex.count(user), out = TokenIndex.count(raw == null ? "" : raw);
        return parse(json, raw, allowed).map(v -> new Verdict(v.label(), v.reason(), in, out));
    }

    /** The verdict in a reply: from the JSON object when there is one, otherwise the first allowed word in the text. Anything else: none. */
    static Optional<Verdict> parse(ObjectMapper json, String raw, Set<String> allowed) {
        if (raw == null) return Optional.empty();
        int open = raw.indexOf('{'), close = raw.lastIndexOf('}');
        if (open >= 0 && close > open) {
            try {
                JsonNode node = json.readTree(raw.substring(open, close + 1));
                String label = node.path("verdict").asText("").strip().toLowerCase(Locale.ROOT);
                if (allowed.contains(label)) return Optional.of(new Verdict(label, node.path("reason").asText(""), 0, 0));
            } catch (Exception ignored) {
                // fall through to the word search
            }
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        String best = null;
        int bestAt = Integer.MAX_VALUE;
        for (String label : allowed) {
            var m = Pattern.compile("\\b" + label + "\\b").matcher(lower);
            if (m.find() && m.start() < bestAt) {
                best = label;
                bestAt = m.start();
            }
        }
        return best == null ? Optional.empty() : Optional.of(new Verdict(best, "", 0, 0));
    }
}
