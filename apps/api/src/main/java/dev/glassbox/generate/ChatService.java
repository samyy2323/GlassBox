package dev.glassbox.generate;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.chunking.TokenIndex;
import dev.glassbox.generate.CitationParser.Parsed;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.Candidate;
import dev.glassbox.trace.TraceAssembler;
import dev.glassbox.trace.TraceModel;
import dev.glassbox.trace.TraceModel.Status;
import dev.glassbox.trace.TraceService;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalPipeline;
import dev.glassbox.retrieval.WorkspaceDefaults;
import dev.glassbox.retrieval.Span;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
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

    private final WorkspaceDefaults workspaceDefaults;
    private final dev.glassbox.observability.LlmMetrics llmMetrics;

    private final RetrievalPipeline pipeline;
    private final PromptBuilder prompts;
    private final LlmProvider llm;
    private final CitationParser citations;
    private final AnswerSanitizer sanitizer;
    private final Tx tx;
    private final TraceService traces;
    private final TraceAssembler assembler;
    private final String embeddingModel;
    private final double temperature;
    private final int maxTokens;

    public ChatService(RetrievalPipeline pipeline, PromptBuilder prompts, LlmProvider llm, CitationParser citations,
                       AnswerSanitizer sanitizer, Tx tx, TraceService traces, TraceAssembler assembler,
                       @Value("${glassbox.embed.default-model}") String embeddingModel,
                       @Value("${glassbox.llm.temperature}") double temperature,
                       @Value("${glassbox.llm.max-tokens}") int maxTokens, WorkspaceDefaults workspaceDefaults,
                       dev.glassbox.observability.LlmMetrics llmMetrics) {
        this.llmMetrics = llmMetrics;
        this.workspaceDefaults = workspaceDefaults;
        this.pipeline = pipeline;
        this.prompts = prompts;
        this.llm = llm;
        this.citations = citations;
        this.sanitizer = sanitizer;
        this.tx = tx;
        this.traces = traces;
        this.assembler = assembler;
        this.embeddingModel = embeddingModel;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
    }

    /**
     * Runs the turn to completion. The caller owns (and closes) the LLM permit. Never throws: failures become an error event.
     * Every outcome (answered, abstained, failed, cancelled) is saved as a trace so X-Ray can explain it afterwards.
     */
    public void answer(UUID workspaceId, UUID userId, String question, Options options, Sink sink) {
        long started = System.nanoTime();
        List<Span> spans = new ArrayList<>();
        Consumer<Span> record = span -> {
            spans.add(span);
            sink.send("trace", span);
        };
        RetrievalPipeline.Result result = null;
        List<LlmProvider.Message> messages = null;
        Parsed parsed = null;
        Map<UUID, String> titles = Map.of();
        StringBuilder answer = new StringBuilder();
        int promptTokens = 0;
        try {
            PipelineConfig config = workspaceDefaults.promoted(workspaceId).orElseGet(() -> PipelineConfig.defaults(embeddingModel)).withReranker(options.reranker())
                    .withTopN(options.topN()).withTokenBudget(options.tokenBudget());
            result = pipeline.retrieve(workspaceId, question, config, record);
            titles = titles(workspaceId, result.candidates().stream().map(Candidate::documentId).distinct().toList());

            long generationStart = System.nanoTime();
            if (result.context().included().isEmpty()) {
                // Nothing relevant was found: abstain without spending an LLM call.
                answer.append(PromptBuilder.NOT_IN_DOCUMENTS);
                sink.send("token", Map.of("t", PromptBuilder.NOT_IN_DOCUMENTS));
                record.accept(new Span("generation", 0, Map.of("skipped", true, "reason", "no context was retrieved")));
            } else {
                messages = prompts.build(question, result.context(), titles);
                promptTokens = messages.stream().mapToInt(m -> TokenIndex.count(m.content())).sum();
                AtomicBoolean first = new AtomicBoolean(true);
                long[] ttft = {0};
                llm.stream(new LlmProvider.Request(messages, temperature, maxTokens), token -> {
                    if (sink.cancelled()) throw new LlmProvider.Cancelled();
                    if (first.compareAndSet(true, false)) ttft[0] = (System.nanoTime() - generationStart) / 1_000_000;
                    answer.append(token);
                    sink.send("token", Map.of("t", token));
                });
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("model", llm.model());
                detail.put("timeToFirstTokenMs", ttft[0]);
                detail.put("promptTokens", promptTokens);
                detail.put("answerTokens", TokenIndex.count(answer.toString()));
                record.accept(new Span("generation", (System.nanoTime() - generationStart) / 1_000_000, detail));
            }

            var clean = sanitizer.sanitize(answer.toString());
            parsed = citations.parse(clean.text(), result.context(), titles);
            sink.send("citations", Map.of("citations", parsed.citations(), "unknown", parsed.unknown()));

            boolean abstained = clean.text().contains(PromptBuilder.NOT_IN_DOCUMENTS);
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            UUID traceId = saveTrace(workspaceId, userId, abstained ? Status.ABSTAINED : Status.OK, question, clean.text(), latencyMs,
                    spans, result, titles, messages, parsed, promptTokens, null);

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("answer", clean.text());
            done.put("sanitized", clean.changed());
            done.put("abstained", abstained);
            done.put("model", llm.model());
            done.put("promptTokens", promptTokens);
            done.put("answerTokens", TokenIndex.count(clean.text()));
            done.put("latencyMs", latencyMs);
            if (traceId != null) done.put("traceId", traceId);
            if (promptTokens > 0) llmMetrics.record(workspaceId, llm.model(), promptTokens, TokenIndex.count(clean.text()));
            sink.send("done", done);
        } catch (LlmProvider.Cancelled cancelled) {
            log.debug("chat cancelled by client");
            saveTrace(workspaceId, userId, Status.CANCELLED, question, answer.toString(), (System.nanoTime() - started) / 1_000_000,
                    spans, result, titles, messages, parsed, promptTokens, "cancelled by the client");
        } catch (RuntimeException e) {
            log.warn("chat failed: {}", e.toString());
            UUID traceId = saveTrace(workspaceId, userId, Status.ERROR, question, answer.toString(), (System.nanoTime() - started) / 1_000_000,
                    spans, result, titles, messages, parsed, promptTokens, e.toString());
            try {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("code", "generation_failed");
                error.put("message", friendly(e));
                if (traceId != null) error.put("traceId", traceId);
                sink.send("error", error);
            } catch (RuntimeException ignored) {
                // client is gone
            }
        }
    }

    /** A trace is diagnostic: failing to save one must never fail the answer. */
    private UUID saveTrace(UUID ws, UUID userId, Status status, String question, String answer, long latencyMs, List<Span> spans,
                           RetrievalPipeline.Result result, Map<UUID, String> titles, List<LlmProvider.Message> messages, Parsed parsed,
                           int promptTokens, String error) {
        try {
            var data = assembler.assemble(result, titles, messages, parsed, new TraceModel.Usage(promptTokens, TokenIndex.count(answer)), error);
            return traces.save(ws, userId, "CHAT", status, question, answer, llm.model(), (int) latencyMs, List.copyOf(spans), data);
        } catch (RuntimeException e) {
            log.warn("could not save trace: {}", e.toString());
            return null;
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
