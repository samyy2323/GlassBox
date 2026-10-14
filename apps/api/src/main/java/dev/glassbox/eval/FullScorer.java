package dev.glassbox.eval;

import dev.glassbox.chunking.TokenIndex;
import dev.glassbox.eval.ClaimSplitter.Claim;
import dev.glassbox.eval.ClaimVerifier.Checked;
import dev.glassbox.generate.AnswerSanitizer;
import dev.glassbox.generate.CitationParser;
import dev.glassbox.generate.LlmProvider;
import dev.glassbox.generate.PromptBuilder;
import dev.glassbox.retrieval.Candidate;
import dev.glassbox.retrieval.RetrievalPipeline;
import dev.glassbox.retrieval.Span;
import dev.glassbox.tenancy.Tx;
import dev.glassbox.trace.TraceAssembler;
import dev.glassbox.trace.TraceModel;
import dev.glassbox.trace.TraceService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The generation half of a full experiment run for one question (SPEC 7): write the answer from the retrieved context exactly as chat
 * does, split it into claims and verify each, have the judge grade correctness and relevance, and score abstention. Every answer also
 * gets a trace, so a question that flipped between two runs opens in X-Ray.
 */
@Component
public class FullScorer {
    private static final Logger log = LoggerFactory.getLogger(FullScorer.class);

    /** Everything the runner stores for the generation side of one result. */
    public record Scored(Map<String, Double> metrics, String answer, Object claimsJson, int tokensIn, int tokensOut, double costUsd, UUID traceId,
                         boolean abstained) {}

    private final LlmProvider llm;
    private final PromptBuilder prompts;
    private final AnswerSanitizer sanitizer;
    private final CitationParser citations;
    private final ClaimSplitter splitter;
    private final ClaimVerifier verifier;
    private final Judge judge;
    private final TraceAssembler assembler;
    private final TraceService traces;
    private final Tx tx;
    private final int maxTokens;
    private final int seed;
    private final double promptUsdPerMillion;
    private final double completionUsdPerMillion;

    public FullScorer(LlmProvider llm, PromptBuilder prompts, AnswerSanitizer sanitizer, CitationParser citations, ClaimSplitter splitter,
                      ClaimVerifier verifier, Judge judge, TraceAssembler assembler, TraceService traces, Tx tx,
                      @Value("${glassbox.llm.max-tokens}") int maxTokens, @Value("${glassbox.eval.seed}") int seed,
                      @Value("${glassbox.cost.prompt-usd-per-million}") double promptUsdPerMillion,
                      @Value("${glassbox.cost.completion-usd-per-million}") double completionUsdPerMillion) {
        this.llm = llm;
        this.prompts = prompts;
        this.sanitizer = sanitizer;
        this.citations = citations;
        this.splitter = splitter;
        this.verifier = verifier;
        this.judge = judge;
        this.assembler = assembler;
        this.traces = traces;
        this.tx = tx;
        this.maxTokens = maxTokens;
        this.seed = seed;
        this.promptUsdPerMillion = promptUsdPerMillion;
        this.completionUsdPerMillion = completionUsdPerMillion;
    }

    public Scored score(UUID ws, MeasurableQuestions.Question q, RetrievalPipeline.Result result, Runnable alive) {
        long started = System.nanoTime();
        List<Span> spans = new ArrayList<>(result.spans());
        Map<UUID, String> titles = titles(ws, result.candidates().stream().map(Candidate::documentId).distinct().toList());

        List<LlmProvider.Message> messages = null;
        String raw;
        int promptTokens = 0;
        long generationStart = System.nanoTime();
        if (result.context().included().isEmpty()) {
            raw = PromptBuilder.NOT_IN_DOCUMENTS; // nothing relevant was found: abstain without spending a model call
            spans.add(new Span("generation", 0, Map.of("skipped", true, "reason", "no context was retrieved")));
        } else {
            messages = prompts.build(q.text(), result.context(), titles);
            promptTokens = messages.stream().mapToInt(m -> TokenIndex.count(m.content())).sum();
            raw = llm.complete(new LlmProvider.Request(messages, 0.0, maxTokens, false, seed));
            alive.run();
            spans.add(new Span("generation", (System.nanoTime() - generationStart) / 1_000_000,
                    Map.of("model", llm.model(), "promptTokens", promptTokens, "answerTokens", TokenIndex.count(raw))));
        }
        var clean = sanitizer.sanitize(raw);
        String answer = clean.text();
        var parsed = citations.parse(answer, result.context(), titles);
        boolean abstained = answer.contains(PromptBuilder.NOT_IN_DOCUMENTS);
        boolean answerable = !q.spans().isEmpty();

        Map<String, Double> m = new LinkedHashMap<>();
        if (answerable) m.put(Metrics.FALSE_ABSTENTION, abstained ? 1.0 : 0.0);
        else m.put(Metrics.ABSTENTION_ACCURACY, abstained ? 1.0 : 0.0);

        int tokensIn = promptTokens, tokensOut = TokenIndex.count(answer);
        Map<String, Object> detail = new LinkedHashMap<>();
        List<Checked> checked = List.of();
        if (!abstained) {
            long t = System.nanoTime();
            List<Claim> claims = splitter.split(answer);
            checked = verifier.verify(claims, result.context(), alive);
            double faithfulness = ClaimVerifier.faithfulness(checked), citationPrecision = ClaimVerifier.citationPrecision(checked);
            if (!Double.isNaN(faithfulness)) m.put(Metrics.FAITHFULNESS, faithfulness);
            if (!Double.isNaN(citationPrecision)) m.put(Metrics.CITATION_PRECISION, citationPrecision);
            spans.add(new Span("verification", (System.nanoTime() - t) / 1_000_000, Map.of("claims", checked.size(), "model", "nli + judge")));
        }

        // correctness and relevance
        Map<String, Object> judged = new LinkedHashMap<>();
        if (!answerable) {
            m.put(Metrics.CORRECTNESS, abstained ? 1.0 : 0.0); // the right answer to an unanswerable question is to say so
        } else if (abstained) {
            m.put(Metrics.CORRECTNESS, 0.0);
            m.put(Metrics.RELEVANCE, 0.0);
        } else {
            Optional<Judge.Verdict> correctness = judge.correctness(q.text(), q.expected(), answer);
            alive.run();
            Optional<Judge.Verdict> relevance = judge.relevance(q.text(), answer);
            alive.run();
            if (correctness.isPresent()) {
                m.put(Metrics.CORRECTNESS, switch (correctness.get().label()) { case "correct" -> 1.0; case "partial" -> 0.5; default -> 0.0; });
                judged.put("correctness", Map.of("verdict", correctness.get().label(), "reason", correctness.get().reason()));
                tokensIn += correctness.get().tokensIn();
                tokensOut += correctness.get().tokensOut();
            } else {
                judged.put("correctness", Map.of("error", "the judge's reply could not be read"));
            }
            if (relevance.isPresent()) {
                m.put(Metrics.RELEVANCE, switch (relevance.get().label()) { case "yes" -> 1.0; case "partly" -> 0.5; default -> 0.0; });
                judged.put("relevance", Map.of("verdict", relevance.get().label(), "reason", relevance.get().reason()));
                tokensIn += relevance.get().tokensIn();
                tokensOut += relevance.get().tokensOut();
            } else {
                judged.put("relevance", Map.of("error", "the judge's reply could not be read"));
            }
        }
        for (Checked c : checked) {
            if (c.source().equals("JUDGE")) {
                tokensIn += 300; // a claim judgement: prompt of about this size, counted rather than metered
                tokensOut += 20;
            }
        }
        detail.put("claims", checked);
        detail.put("verifier", ClaimVerifier.VERSION);
        detail.put("rubricVersion", Judge.RUBRIC_VERSION);
        detail.put("judge", judged);

        UUID traceId = saveTrace(ws, q.text(), answer, abstained, started, spans, result, titles, messages, parsed, promptTokens);
        double cost = (tokensIn * promptUsdPerMillion + tokensOut * completionUsdPerMillion) / 1_000_000.0;
        return new Scored(m, answer, detail, tokensIn, tokensOut, cost, traceId, abstained);
    }

    private UUID saveTrace(UUID ws, String question, String answer, boolean abstained, long started, List<Span> spans, RetrievalPipeline.Result result,
                           Map<UUID, String> titles, List<LlmProvider.Message> messages, CitationParser.Parsed parsed, int promptTokens) {
        try {
            var data = assembler.assemble(result, titles, messages, parsed, new TraceModel.Usage(promptTokens, TokenIndex.count(answer)), null);
            return traces.save(ws, null, "EXPERIMENT", abstained ? TraceModel.Status.ABSTAINED : TraceModel.Status.OK, question, answer, llm.model(),
                    (int) ((System.nanoTime() - started) / 1_000_000), List.copyOf(spans), data);
        } catch (RuntimeException e) {
            log.warn("could not save the trace of an experiment answer: {}", e.toString());
            return null; // a trace is diagnostic: failing to save one must not fail the run
        }
    }

    private Map<UUID, String> titles(UUID ws, List<UUID> documentIds) {
        Map<UUID, String> out = new HashMap<>();
        if (documentIds.isEmpty()) return out;
        tx.workspace(ws, null, j -> {
            j.query("SELECT id, title FROM documents WHERE id = ANY (?)", ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", documentIds.toArray())),
                    rs -> { out.put(rs.getObject("id", UUID.class), rs.getString("title")); });
            return null;
        });
        return out;
    }
}
