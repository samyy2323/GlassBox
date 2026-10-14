package dev.glassbox.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.chunking.TokenIndex;
import dev.glassbox.generate.CitationParser.Parsed;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalPipeline;
import dev.glassbox.retrieval.Span;
import dev.glassbox.tenancy.Tx;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * One chat turn: retrieve, generate, check citations. Events are pushed to a {@link Sink} as they happen
 * (trace, token, citations, done, error), so the same code serves SSE and tests.
 */
@Service
public class ChatService {
    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    public interface Sink {
        void send(String event, Object data);

        boolean cancelled();
    }

    public record Options(Reranker.Type reranker, int topN, int tokenBudget) {}

    private final RetrievalPipeline pipeline;
    private final PromptBuilder prompts;
    private final LlmProvider llm;
    private final CitationParser citations;
    private final AnswerSanitizer sanitizer;
    private final Tx tx;
    private final String embeddingModel;
    private final double temperature;
    private final int maxTokens;

    public ChatService(RetrievalPipeline pipeline, PromptBuilder prompts, LlmProvider llm, CitationParser citations,
                       AnswerSanitizer sanitizer, Tx tx,
                       @Value("${glassbox.embed.default-model}") String embeddingModel,
                       @Value("${glassbox.llm.temperature}") double temperature,
                       @Value("${glassbox.llm.max-tokens}") int maxTokens) {
        this.pipeline = pipeline;
        this.prompts = prompts;
        this.llm = llm;
        this.citations = citations;
        this.sanitizer = sanitizer;
        this.tx = tx;
        this.embeddingModel = embeddingModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    /** Runs the turn to completion. The caller owns (and closes) the LLM permit. Never throws: failures become an error event. */
    public void answer(UUID workspaceId, String question, Options options, Sink sink) {
        long started = System.nanoTime();
        try {
            PipelineConfig config = PipelineConfig.defaults(embeddingModel).withReranker(options.reranker())
                    .withTopN(options.topN()).withTokenBudget(options.tokenBudget());
            var result = pipeline.retrieve(workspaceId, question, config, span -> sink.send("trace", span));
            Map<UUID, String> titles = titles(workspaceId, result.context().included().stream().map(c -> c.chunk().documentId()).distinct().toList());

            StringBuilder answer = new StringBuilder();
            int promptTokens = 0;
            long generationStart = System.nanoTime();
            Long firstToken = null;
            if (result.context().included().isEmpty()) {
                // Nothing relevant was found: abstain without spending an LLM call.
                answer.append(PromptBuilder.NOT_IN_DOCUMENTS);
                sink.send("token", Map.of("t", PromptBuilder.NOT_IN_DOCUMENTS));
                sink.send("trace", new Span("generation", 0, Map.of("skipped", true, "reason", "no context was retrieved")));
            } else {
                var messages = prompts.build(question, result.context(), titles);
                promptTokens = messages.stream().mapToInt(m -> TokenIndex.count(m.content())).sum();
                AtomicBoolean first = new AtomicBoolean(true);
                long[] ttft = {0};
                llm.stream(new LlmProvider.Request(messages, temperature, maxTokens), token -> {
                    if (sink.cancelled()) throw new LlmProvider.Cancelled();
                    if (first.compareAndSet(true, false)) ttft[0] = (System.nanoTime() - generationStart) / 1_000_000;
                    answer.append(token);
                    sink.send("token", Map.of("t", token));
                });
                firstToken = ttft[0];
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("model", llm.model());
                detail.put("timeToFirstTokenMs", firstToken);
                detail.put("promptTokens", promptTokens);
                detail.put("answerTokens", TokenIndex.count(answer.toString()));
                sink.send("trace", new Span("generation", (System.nanoTime() - generationStart) / 1_000_000, detail));
            }

            var clean = sanitizer.sanitize(answer.toString());
            Parsed parsed = citations.parse(clean.text(), result.context(), titles);
            sink.send("citations", Map.of("citations", parsed.citations(), "unknown", parsed.unknown()));

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("answer", clean.text());
            done.put("sanitized", clean.changed());
            done.put("abstained", clean.text().contains(PromptBuilder.NOT_IN_DOCUMENTS));
            done.put("model", llm.model());
            done.put("promptTokens", promptTokens);
            done.put("answerTokens", TokenIndex.count(clean.text()));
            done.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
            sink.send("done", done);
        } catch (LlmProvider.Cancelled cancelled) {
            log.debug("chat cancelled by client");
        } catch (RuntimeException e) {
            log.warn("chat failed: {}", e.toString());
            try {
                sink.send("error", Map.of("code", "generation_failed", "message", friendly(e)));
            } catch (RuntimeException ignored) {
                // client is gone
            }
        }
    }

    private Map<UUID, String> titles(UUID ws, List<UUID> documentIds) {
        if (documentIds.isEmpty()) return Map.of();
        String ids = "{" + String.join(",", documentIds.stream().map(UUID::toString).toList()) + "}";
        Map<UUID, String> out = new HashMap<>();
        tx.workspace(ws, null, j -> {
            j.query("SELECT id, title FROM documents WHERE id = ANY (?::uuid[])", rs -> { out.put(rs.getObject("id", UUID.class), rs.getString("title")); }, ids);
            return null;
        });
        return out;
    }

    /** Messages shown to end users; details stay in the log. */
    private static String friendly(RuntimeException e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("Connection refused") || m.contains("connect") || m.contains("LLM endpoint")) {
            return "The language model is not reachable right now. Please try again shortly.";
        }
        return "Something went wrong while generating the answer.";
    }
}
