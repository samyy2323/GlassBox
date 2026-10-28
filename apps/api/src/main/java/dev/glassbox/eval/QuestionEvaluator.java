package dev.glassbox.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.diagnose.FailureClassifier;
import dev.glassbox.eval.RetrievalMetrics.Gold;
import dev.glassbox.eval.RetrievalMetrics.Ref;
import dev.glassbox.retrieval.Candidate;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalPipeline;
import dev.glassbox.retrieval.Span;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Measures one question under one configuration and writes the result of a run: retrieval metrics, the gold-span trace and failure label,
 * and in a full run the answer, its verified claims and the judge's grades. Shared by experiments and AutoTune, so a finalist of an AutoTune
 * run is scored, diagnosed and traced by exactly the code that scores a hand-made experiment.
 */
@Component
public class QuestionEvaluator {
    private static final int KEPT_CANDIDATES = 20;
    private static final UUID NO_DOCUMENT = new UUID(0, 0);

    private final Tx tx;
    private final ObjectMapper json;
    private final RetrievalPipeline pipeline;
    private final FullScorer scorer;

    private final dev.glassbox.observability.LlmMetrics llmMetrics;

    public QuestionEvaluator(Tx tx, ObjectMapper json, RetrievalPipeline pipeline, FullScorer scorer, dev.glassbox.observability.LlmMetrics llmMetrics) {
        this.llmMetrics = llmMetrics;
        this.tx = tx;
        this.json = json;
        this.pipeline = pipeline;
        this.scorer = scorer;
    }

    static Ref ref(Candidate c) {
        return new Ref(c.documentId(), c.charStart(), c.charEnd());
    }

    /** Retrieves for one question, scores it (and, in a full run, answers and judges it), and writes the result once. */
    public void evaluate(UUID ws, UUID runId, MeasurableQuestions.Question q, PipelineConfig config, boolean full, Runnable alive) throws JsonProcessingException {
        long started = System.nanoTime();
        RetrievalPipeline.Result result = pipeline.retrieve(ws, q.text(), config);
        List<Gold> gold = q.spans().stream().map(s -> new Gold(s.documentId(), s.charStart(), s.charEnd())).toList();
        Map<String, Double> metrics = new LinkedHashMap<>();
        String failure = null;
        List<Map<String, Object>> retrieved = new ArrayList<>(), finalContext = new ArrayList<>();

        if (!gold.isEmpty()) {
            List<Ref> ranked = result.reranked().stream().map(QuestionEvaluator::ref).toList();
            List<Ref> context = result.context().included().stream().map(c -> ref(c.chunk())).toList();
            metrics.putAll(RetrievalMetrics.compute(ranked, context, gold));
            for (int i = 0; i < Math.min(KEPT_CANDIDATES, result.reranked().size()); i++) {
                Candidate c = result.reranked().get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank", i + 1);
                m.put("chunkId", c.chunkId());
                m.put("documentId", c.documentId());
                m.put("charStart", c.charStart());
                m.put("charEnd", c.charEnd());
                m.put("hit", gold.stream().anyMatch(g -> RetrievalMetrics.hits(ref(c), g)));
                if (c.vecRank() != null) m.put("vecRank", c.vecRank());
                if (c.kwRank() != null) m.put("kwRank", c.kwRank());
                retrieved.add(m);
            }
        }
        result.context().included().forEach(c -> finalContext.add(Map.of("label", c.label(), "chunkId", c.chunk().chunkId(), "documentId", c.chunk().documentId(),
                "charStart", c.chunk().charStart(), "charEnd", c.chunk().charEnd())));

        String answer = null;
        Object claims = null;
        int tokensIn = 0, tokensOut = 0;
        double cost = 0;
        UUID traceId = null;
        if (full) {
            FullScorer.Scored scored = scorer.score(ws, q, result, alive);
            metrics.putAll(scored.metrics());
            answer = scored.answer();
            claims = scored.claimsJson();
            tokensIn = scored.tokensIn();
            tokensOut = scored.tokensOut();
            cost = scored.costUsd();
            llmMetrics.record(scorer.model(), tokensIn, tokensOut, cost);
            traceId = scored.traceId();
        }
        long latency = full ? (System.nanoTime() - started) / 1_000_000 : result.spans().stream().mapToLong(Span::millis).sum();

        List<FailureClassifier.SpanTrace> traces = new ArrayList<>();
        List<Map<String, Object>> spanDetail = new ArrayList<>();
        for (Gold g : gold) traceSpan(ws, result, g, traces, spanDetail);
        boolean abstained = full && (q.spans().isEmpty() ? metrics.getOrDefault(Metrics.ABSTENTION_ACCURACY, 0.0) >= 1.0
                : metrics.getOrDefault(Metrics.FALSE_ABSTENTION, 0.0) >= 1.0);
        FailureClassifier.Diagnosis diagnosis = FailureClassifier.classify(new FailureClassifier.Input(q.qtype(), traces, full, abstained,
                metrics.get(Metrics.CORRECTNESS), metrics.get(Metrics.FAITHFULNESS), q.flags().contains("AMBIGUOUS") || q.flags().contains("LOW_ANSWERABILITY")));
        failure = diagnosis == null ? null : diagnosis.stage().name();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("spans", spanDetail);
        detail.put("contextTokens", result.context().tokensUsed());
        if (diagnosis != null) {
            detail.put("stage", diagnosis.stage());
            if (diagnosis.sub() != null) detail.put("sub", diagnosis.sub());
            if (diagnosis.lostAt() != null) detail.put("lostAt", diagnosis.lostAt());
        }
        String detailJson = json.writeValueAsString(detail);

        String metricsJson = json.writeValueAsString(metrics), retrievedJson = json.writeValueAsString(retrieved), contextJson = json.writeValueAsString(finalContext);
        String claimsJson = claims == null ? null : json.writeValueAsString(claims);
        String fail = failure, ans = answer;
        int tin = tokensIn, tout = tokensOut;
        double usd = cost;
        UUID trace = traceId;
        tx.workspace(ws, null, j -> {
            int inserted = j.update("INSERT INTO run_results (workspace_id, run_id, question_id, retrieved, final_context, answer, claims, metrics, failure_stage, latency_ms, "
                            + "tokens_in, tokens_out, cost_usd, trace_id, failure_detail) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb) "
                            + "ON CONFLICT (run_id, question_id) DO NOTHING",
                    ws, runId, q.id(), retrievedJson, contextJson, ans, claimsJson, metricsJson, fail, (int) latency, tin, tout, usd, trace, detailJson);
            if (inserted > 0) j.update("UPDATE runs SET samples_done = samples_done + 1 WHERE id = ?", runId);
            return null;
        });
    }

    /**
     * Follows one gold span through the pipeline. The chunk-size lookup (does any single chunk of this chunking cover the span?) is only made
     * for a span that did not reach the prompt, since only then does it decide the label.
     */
    private void traceSpan(UUID ws, RetrievalPipeline.Result result, Gold g, List<FailureClassifier.SpanTrace> traces, List<Map<String, Object>> detail) {
        Integer fusedRank = firstHit(result.candidates(), g), finalRank = firstHit(result.reranked(), g);
        boolean inRanked = firstHit(result.ranked(), g) != null;
        boolean inContext = result.context().included().stream().anyMatch(c -> RetrievalMetrics.hits(ref(c.chunk()), g));
        Double cover = null;
        String dropped = null;
        if (!inContext) {
            cover = result.chunkingId() == null ? 0.0 : bestCover(ws, result.chunkingId(), g);
            dropped = result.context().dropped().stream().filter(d -> RetrievalMetrics.hits(ref(d.chunk()), g)).map(d -> d.reason().name()).findFirst().orElse(null);
        }
        traces.add(new FailureClassifier.SpanTrace(cover, finalRank != null, inRanked, inContext));
        Map<String, Object> m = new LinkedHashMap<>();
        if (cover != null) m.put("bestCover", Math.round(cover * 1000) / 1000.0);
        if (fusedRank != null) m.put("fusedRank", fusedRank);
        if (finalRank != null) m.put("finalRank", finalRank);
        m.put("inRanked", inRanked);
        m.put("inContext", inContext);
        if (dropped != null) m.put("dropped", dropped);
        detail.add(m);
    }

    /** 1-based position of the first chunk that hits the span, or null. */
    private static Integer firstHit(List<Candidate> list, Gold g) {
        for (int i = 0; i < list.size(); i++) if (RetrievalMetrics.hits(ref(list.get(i)), g)) return i + 1;
        return null;
    }

    /** The largest share of the span that one chunk of the chunking covers. */
    private double bestCover(UUID ws, UUID chunkingId, Gold g) {
        int length = g.end() - g.start();
        if (length <= 0) return 0;
        Integer overlap = tx.workspace(ws, null, j -> j.queryForObject(
                "SELECT coalesce(max(least(char_end, ?) - greatest(char_start, ?)), 0) FROM chunks "
                        + "WHERE chunking_id = ? AND document_id = ? AND NOT superseded AND char_end > ? AND char_start < ?",
                Integer.class, g.end(), g.start(), chunkingId, g.documentId(), g.start(), g.end()));
        return Math.min(1.0, Math.max(0, overlap == null ? 0 : overlap) / (double) length);
    }

    /** Aggregates a finished run into run_metrics and marks it DONE. Rewritten from scratch each time, so a resumed run never doubles rows. */
    public void finishRun(UUID ws, UUID runId, MeasurableQuestions.Loaded loaded) {
        Map<UUID, MeasurableQuestions.Question> byId = new HashMap<>();
        loaded.questions().forEach(q -> byId.put(q.id(), q));
        Map<UUID, Integer> clusterIds = new HashMap<>();
        List<RunAggregates.Score> scores = new ArrayList<>();
        tx.workspace(ws, null, j -> {
            j.query("SELECT question_id, metrics::text AS metrics, latency_ms FROM run_results WHERE run_id = ? ORDER BY question_id", rs -> {
                MeasurableQuestions.Question q = byId.get(rs.getObject("question_id", UUID.class));
                if (q == null) return;
                try {
                    Map<String, Double> m = json.readValue(rs.getString("metrics"), new TypeReference<Map<String, Double>>() {});
                    UUID doc = q.clusterDocument() == null ? NO_DOCUMENT : q.clusterDocument();
                    int cluster = clusterIds.computeIfAbsent(doc, d -> clusterIds.size());
                    scores.add(new RunAggregates.Score(q.qtype(), cluster, m, rs.getInt("latency_ms")));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }, runId);
            return null;
        });
        List<RunAggregates.Row> rows = RunAggregates.compute(scores, runId.hashCode());
        tx.workspace(ws, null, j -> {
            j.update("DELETE FROM run_metrics WHERE run_id = ?", runId);
            for (RunAggregates.Row r : rows) {
                j.update("INSERT INTO run_metrics (run_id, workspace_id, metric, slice, method, value, ci_low, ci_high, n) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        runId, ws, r.metric(), r.slice(), r.method(), nan(r.value()), nan(r.ciLow()), nan(r.ciHigh()), r.n());
            }
            j.update("UPDATE runs SET status = 'DONE', finished_at = now() WHERE id = ?", runId);
            return null;
        });
    }

    private static Double nan(double d) {
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }
}
