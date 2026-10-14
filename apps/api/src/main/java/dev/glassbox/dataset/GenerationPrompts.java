package dev.glassbox.dataset;

import dev.glassbox.generate.LlmProvider.Message;
import java.util.List;

/** Prompts for test set generation. Kept in one place so their wording can be measured and changed deliberately. */
public final class GenerationPrompts {
    private static final int MAX_PASSAGE_CHARS = 1800;

    private GenerationPrompts() {}

    public static final String GENERATOR_SYSTEM = """
            You write exam questions for testing a document search system. You are given one or two passages.
            Write ONE question that can be answered from the passages, and its short correct answer.
            Rules:
            - The question must make sense on its own, to someone who has not seen the passages. Name the specific thing it is about.
            - Never write "the passage", "the document", "the text" or "the author" in the question.
            - Do not copy a whole sentence from the passage into the question; ask about a fact in it.
            - The answer must be stated in the passages, in at most two sentences.
            Reply with one JSON object and nothing else: {"question": "...", "expected_answer": "..."}""";

    private static String instruction(String type) {
        return switch (type) {
            case QuestionSampler.NUMERIC -> "The answer must be a number, amount, date, duration or percentage with its unit.";
            case QuestionSampler.MULTI_HOP -> "The question must need facts from BOTH passages together; one passage alone must not be enough.";
            case QuestionSampler.COMPARISON -> "The question must compare the two passages (a difference, similarity or relationship); one passage alone must not be enough.";
            default -> "The question asks for one specific fact.";
        };
    }

    public static List<Message> generator(String type, List<QuestionSampler.Chunk> passages) {
        StringBuilder user = new StringBuilder();
        char label = 'A';
        for (QuestionSampler.Chunk c : passages) {
            String text = c.text().strip();
            if (text.length() > MAX_PASSAGE_CHARS) text = text.substring(0, MAX_PASSAGE_CHARS);
            user.append("Passage ").append(label++).append(" (from \"").append(c.title()).append("\"):\n").append(text).append("\n\n");
        }
        user.append(instruction(type));
        return List.of(Message.system(GENERATOR_SYSTEM), Message.user(user.toString()));
    }

    public static final String SELF_CONTAINED_SYSTEM = """
            You check exam questions. Decide whether a question can be understood and answered by someone who has NOT seen any
            document: it must name what it is about and must not point at "the text", "the passage" or "it" without saying what.
            Reply with exactly one word: YES or NO.""";

    public static List<Message> selfContained(String question) {
        return List.of(Message.system(SELF_CONTAINED_SYSTEM), Message.user("Question: " + question));
    }

    public static final String ANSWER_SYSTEM = """
            Answer the question using only the text below. Reply in at most two sentences. If the text does not contain the
            answer, reply exactly: NOT STATED.""";

    public static List<Message> answer(String question, List<String> evidence) {
        return List.of(Message.system(ANSWER_SYSTEM), Message.user("Text:\n" + String.join("\n...\n", evidence) + "\n\nQuestion: " + question));
    }
}
