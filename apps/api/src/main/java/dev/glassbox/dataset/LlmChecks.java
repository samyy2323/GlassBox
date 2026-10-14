package dev.glassbox.dataset;

import dev.glassbox.generate.LlmProvider;
import dev.glassbox.generate.LlmProvider.Request;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The two quality checks that need a model: is the question self-contained, and can a model answer it from the gold span
 * alone. The judge for the second one is lexical agreement with the expected answer (see {@link QualityFilters#ANSWER_AGREEMENT});
 * a model-based judge arrives with the evaluation metrics in P3.
 */
@Component
public class LlmChecks {
    private final LlmProvider llm;
    private final boolean enabled;

    public LlmChecks(LlmProvider llm, @Value("${glassbox.testset.llm-filters}") boolean enabled) {
        this.llm = llm;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Anything other than a clear NO counts as self-contained: a flaky judge should not flag good questions. */
    public boolean selfContained(String question) {
        String reply = llm.complete(new Request(GenerationPrompts.selfContained(question), 0.0, 8)).strip().toUpperCase();
        return !reply.startsWith("NO");
    }

    public String answerFrom(String question, List<String> evidence) {
        return llm.complete(new Request(GenerationPrompts.answer(question, evidence), 0.0, 160));
    }
}
