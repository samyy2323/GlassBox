package dev.glassbox.eval;

import static dev.glassbox.workspace.WorkspaceService.audit;
import static dev.glassbox.workspace.WorkspaceService.require;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.auth.Principal;
import dev.glassbox.common.ApiException;
import dev.glassbox.chunking.Chunker;
import dev.glassbox.embed.EmbeddingProviders;
import dev.glassbox.eval.ExperimentModel.*;
import dev.glassbox.generate.CachedLlm;
import dev.glassbox.cost.CostEstimator;
import dev.glassbox.cost.BudgetService;
import dev.glassbox.index.IndexService;
import dev.glassbox.generate.LlmProvider;
import dev.glassbox.generate.PromptBuilder;
import dev.glassbox.jobs.JobQueue;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.Chunking;
import dev.glassbox.retrieval.ChunkingResolver;
import dev.glassbox.retrieval.HybridSql;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.transform.Transformer;
import dev.glassbox.retrieval.RetrievalParams;
import dev.glassbox.tenancy.Role;
import dev.glassbox.tenancy.Tx;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Experiments: one FINAL test set version plus a list of configurations, each of which becomes a run (SPEC 7). The first
 * configuration is the baseline everything else is compared against. Running happens in {@link RunExperimentHandler}.
 * A RETRIEVAL experiment only retrieves and scores against the gold evidence; a FULL one also generates, verifies claims and judges.
 */
@Service
public class ExperimentService {
    public static final int MAX_CONFIGS = 8;
    /** Rough size of a full run per question, for the estimate: an answer prompt of about 2000 tokens plus a few short judge calls. */
    private static final int ANSWER_TOKENS = 2200;
    private static final int JUDGE_CALL_TOKENS = 450;
    private static final int LLM_CALLS_PER_QUESTION = 5;
    private static final TypeReference<Map<String, Double>> METRICS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    private final Tx tx;
    private final ObjectMapper json;
    private final JobQueue queue;
    private final LlmProvider llm;
    private final NliProvider nli;
    private final CalibrationService calibration;
    private final String embeddingModel;
    private final ChunkingResolver chunkings;
    private final EmbeddingProviders providers;
    private final IndexService indexes;
    private final int seed;
    private final int maxTokens;
    private final BudgetService budgets;
    private final CachedLlm cache;

    public ExperimentService(Tx tx, ObjectMapper json, JobQueue queue, LlmProvider llm, NliProvider nli, CalibrationService calibration,
                             ChunkingResolver chunkings, EmbeddingProviders providers, IndexService indexes,
                             @Value("${glassbox.embed.default-model}") String embeddingModel, @Value("${glassbox.eval.seed}") int seed,
                             @Value("${glassbox.llm.max-tokens}") int maxTokens, BudgetService budgets, CachedLlm cache) {
        this.tx = tx;
        this.json = json;
        this.queue = queue;
        this.llm = llm;
        this.nli = nli;
        this.calibration = calibration;
        this.embeddingModel = embeddingModel;
        this.chunkings = chunkings;
        this.providers = providers;
        this.indexes = indexes;
        this.seed = seed;
        this.maxTokens = maxTokens;
        this.budgets = budgets;
        this.cache = cache;
    }

    // ------------------------------------------------------------------ configurations

    /** A user's configuration to a real PipelineConfig, with every value checked: the search runs with whatever this returns. */
    PipelineConfig toConfig(ConfigInput in) {
        RetrievalParams.Mode mode = parse(RetrievalParams.Mode.class, in.mode(), RetrievalParams.Mode.HYBRID, "mode");
        RetrievalParams.KeywordMatch keyword = parse(RetrievalParams.KeywordMatch.class, in.keywordMatch(), RetrievalParams.KeywordMatch.ANY, "keywordMatch");
        Reranker.Type reranker = parse(Reranker.Type.class, in.reranker(), Reranker.Type.NONE, "reranker");
        int kFused = within(in.kFused(), 50, 1, 200, "kFused");
        int rrfK = within(in.rrfK(), 60, 1, 1000, "rrfK");
        double wVec = within(in.wVec(), 1.0, 0, 10, "wVec"), wKw = within(in.wKw(), 1.0, 0, 10, "wKw");
        if (mode == RetrievalParams.Mode.HYBRID && wVec + wKw <= 0) throw ApiException.badRequest("A hybrid search needs a positive weight for at least one side");
        int topN = within(in.topN(), 5, 1, 50, "topN");
        int budget = within(in.tokenBudget(), 2000, 100, 32000, "tokenBudget");
        int pool = Math.max(50, kFused);
        String model = in.embeddingModel() == null || in.embeddingModel().isBlank() ? embeddingModel : in.embeddingModel().strip();
        if (!HybridSql.MODEL_DIMS.containsKey(model) || !providers.has(model)) {
            throw ApiException.badRequest("embeddingModel must be one of " + providers.available());
        }
        Chunking chunking;
        try {
            chunking = in.chunking();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        // The chunking every upload gets is stored as "no chunking named", so its config (and hash) is the one P3 experiments already have.
        if (chunking != null && sameChunker(chunking.chunker(), chunkings.defaultChunker())) chunking = null;
        Transformer transformer = parse(Transformer.class, in.transformer(), Transformer.NONE, "transformer");
        RetrievalParams params = new RetrievalParams(mode, model, pool, pool, rrfK, wVec, wKw, kFused, 100, keyword);
        return new PipelineConfig(params, reranker, Math.max(50, topN), topN, budget, chunking, transformer);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** What a caller may attach to a new experiment: its own cost cap in dollars and in model calls, and the confirmation of an estimate above the threshold. */
    public record Limits(Double capUsd, Integer capCalls, boolean confirmed) {
        public static final Limits NONE = new Limits(null, null, false);
    }

    private static boolean sameChunker(Chunker a, Chunker b) {
        return a.strategy().equals(b.strategy()) && a.params().equals(b.params());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, E fallback, String field) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(field + " must be one of " + List.of(type.getEnumConstants()));
        }
    }

    private static int within(Integer v, int fallback, int min, int max, String field) {
        int x = v == null ? fallback : v;
        if (x < min || x > max) throw ApiException.badRequest(field + " must be between " + min + " and " + max);
        return x;
    }

    private static double within(Double v, double fallback, double min, double max, String field) {
        double x = v == null ? fallback : v;
        if (Double.isNaN(x) || x < min || x > max) throw ApiException.badRequest(field + " must be between " + min + " and " + max);
        return x;
    }

    private List<PipelineConfig> validate(List<ConfigInput> inputs) {
        if (inputs == null || inputs.isEmpty()) throw ApiException.badRequest("Choose at least one configuration to measure");
        if (inputs.size() > MAX_CONFIGS) throw ApiException.badRequest("At most " + MAX_CONFIGS + " configurations per experiment");
        Set<String> names = new HashSet<>();
        List<PipelineConfig> out = new ArrayList<>();
        for (ConfigInput in : inputs) {
            String name = in.name() == null ? "" : in.name().strip();
            if (name.isEmpty() || name.length() > 80) throw ApiException.badRequest("Every configuration needs a name of 1 to 80 characters");
            if (!names.add(name)) throw ApiException.badRequest("Two configurations are called \"" + name + "\"");
            out.add(toConfig(in));
        }
        return out;
    }

    private String hash(PipelineConfig config) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(config).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The stored row for a configuration, created on first use; the same configuration always maps to the same row. */
    public UUID upsertConfig(JdbcTemplate j, UUID ws, PipelineConfig config) {
        return j.queryForObject("INSERT INTO pipeline_configs (workspace_id, config, config_hash) VALUES (?, ?::jsonb, ?) "
                + "ON CONFLICT (workspace_id, config_hash) DO UPDATE SET config = EXCLUDED.config RETURNING id", UUID.class, ws, write(config), hash(config));
    }

    /** RETRIEVAL (default) or FULL. */
    private static String parseMode(String mode) {
        if (mode == null || mode.isBlank()) return "RETRIEVAL";
        String m = mode.strip().toUpperCase();
        if (!m.equals("RETRIEVAL") && !m.equals("FULL")) throw ApiException.badRequest("mode must be RETRIEVAL or FULL");
        return m;
    }

    // ------------------------------------------------------------------ estimate and create

    /** {@code transformCalls} and {@code transformTokens}: what the query transformers of all the configurations add to one question. */
    private Map<String, Object> estimateFor(JdbcTemplate j, String mode, int questions, int runs, int transformCalls, int transformTokens) {
        boolean full = mode.equals("FULL");
        long qr = (long) questions * runs;
        int judgeCalls = LLM_CALLS_PER_QUESTION - 1;
        CostEstimator.Usage usage = new CostEstimator.Usage(
                qr * 24,                                                                    // each question is embedded once per run
                full ? qr * ANSWER_TOKENS : 0, full ? qr * 160 : 0, full ? (int) qr : 0,    // the answer
                full ? qr * judgeCalls * JUDGE_CALL_TOKENS : 0, full ? qr * judgeCalls * 30 : 0, full ? (int) (qr * judgeCalls) : 0,   // claim checks and grades
                Math.round((long) questions * transformTokens * 0.8), Math.round((long) questions * transformTokens * 0.2), questions * transformCalls,   // query rewriting
                0, 0, 0);                                                                   // question synthesis happens when a test set is written, not here
        CostEstimator.Breakdown bd = CostEstimator.price(usage, budgets.price(j, llm.model()), 0, cache.hitRate());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("questions", questions);
        m.put("retrievalCalls", questions * runs);
        m.put("llmCalls", (long) bd.calls());
        m.put("tokens", bd.tokens() - bd.embeddings().tokens());   // model tokens: embeddings are not billed by the model and are shown in the breakdown
        m.put("usd", bd.usd());
        m.put("breakdown", bd.asMap());
        return m;
    }

    private static void checkLimit(Integer limit) {
        if (limit != null && (limit < 1 || limit > 10_000)) throw ApiException.badRequest("questionLimit must be between 1 and 10000");
    }

    public Estimate estimate(Principal p, UUID ws, UUID testsetId, List<ConfigInput> configs, String modeText, Integer questionLimit) {
        String mode = parseMode(modeText);
        checkLimit(questionLimit);
        List<PipelineConfig> validated = validate(configs);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            String status = testsetStatus(j, testsetId);
            MeasurableQuestions.Loaded loaded = MeasurableQuestions.load(j, json, testsetId, mode.equals("FULL"), questionLimit);
            List<String> notes = new ArrayList<>();
            if (questionLimit != null) notes.add("Only a fixed random sample of up to " + questionLimit + " questions is measured, so the intervals are wider.");
            if (!status.equals("FINAL")) notes.add("This test set is " + status.toLowerCase() + ". Only a final version can be measured.");
            if (loaded.stale() > 0) notes.add(loaded.stale() + " question(s) are left out because their document changed since the test set was written.");
            if (loaded.withoutEvidence() > 0 && mode.equals("RETRIEVAL")) notes.add(loaded.withoutEvidence() + " question(s) have no gold evidence (unanswerable) and are not part of retrieval metrics.");
            if (mode.equals("FULL")) {
                notes.add("Full runs write an answer for every question, check each statement against the documents and have a judge grade it: about " + LLM_CALLS_PER_QUESTION
                        + " model calls per question and run. On a small local model expect roughly a minute or more per answer.");
                notes.add("The model that writes the answers (" + llm.model() + ") also acts as judge, and models tend to favour their own output.");
            } else {
                notes.add("Retrieval only: no text is generated, so this costs no model tokens and runs in seconds.");
            }
            for (IndexService.Need need : indexes.needs(validated)) {
                String line = indexes.describe(ws, need);
                if (line != null) notes.add(line);
            }
            int tc = validated.stream().mapToInt(c -> c.transformer() == null ? 0 : c.transformer().llmCalls()).sum();
            int tt = validated.stream().mapToInt(c -> c.transformer() == null ? 0 : c.transformer().tokens()).sum();
            if (tc > 0) notes.add("Query transformers call the model once per question before the search (" + tc + " call(s) per question across the configurations); answers to a repeated question come from the cache.");
            Map<String, Object> e = estimateFor(j, mode, loaded.questions().size(), validated.size(), tc, tt);
            boolean needsConfirmation = budgets.needsConfirmation(j, (Double) e.get("usd"));
            return new Estimate(loaded.questions().size(), loaded.stale(), validated.size(), (Integer) e.get("retrievalCalls"), ((Number) e.get("llmCalls")).intValue(),
                    ((Number) e.get("tokens")).longValue(), (Double) e.get("usd"), notes, castMap(e.get("breakdown")), needsConfirmation);
        });
    }

    public ExperimentView create(Principal p, UUID ws, UUID testsetId, String name, String modeText, List<ConfigInput> configs, Integer questionLimit, Limits limits) {
        return create(p, ws, testsetId, name, modeText, configs, questionLimit, limits, "MANUAL");
    }

    /** {@code kind} is MANUAL for a person's experiment and REGRESSION for one started by a gate or a document change. */
    public ExperimentView create(Principal p, UUID ws, UUID testsetId, String name, String modeText, List<ConfigInput> configs, Integer questionLimit, Limits limits,
                                 String kind) {
        if (!kind.equals("MANUAL") && !kind.equals("REGRESSION")) throw new IllegalArgumentException("kind " + kind);
        if (limits.capUsd() != null && limits.capUsd() < 0 || limits.capCalls() != null && limits.capCalls() < 1) throw ApiException.badRequest("A cap must be positive");
        String mode = parseMode(modeText);
        checkLimit(questionLimit);
        String cleanName = name == null ? "" : name.strip();
        if (cleanName.isEmpty() || cleanName.length() > 120) throw ApiException.badRequest("An experiment needs a name of 1 to 120 characters");
        List<PipelineConfig> validated = validate(configs);
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            budgets.checkConcurrency(j);
            if (!testsetStatus(j, testsetId).equals("FINAL")) throw ApiException.conflict("Only a final test set can be measured. Finalize it first.");
            MeasurableQuestions.Loaded loaded = MeasurableQuestions.load(j, json, testsetId, mode.equals("FULL"), questionLimit);
            if (loaded.questions().isEmpty()) throw ApiException.conflict("This test set has no accepted question with intact evidence to measure");

            int tc = validated.stream().mapToInt(c -> c.transformer() == null ? 0 : c.transformer().llmCalls()).sum();
            int tt = validated.stream().mapToInt(c -> c.transformer() == null ? 0 : c.transformer().tokens()).sum();
            Map<String, Object> estimate = estimateFor(j, mode, loaded.questions().size(), validated.size(), tc, tt);
            BudgetService.Verdict verdict = budgets.check(j, (Double) estimate.get("usd"), limits.capUsd(), limits.confirmed());
            if (limits.capCalls() != null && ((Number) estimate.get("llmCalls")).longValue() > limits.capCalls()) {
                throw ApiException.conflict("This run is estimated at " + estimate.get("llmCalls") + " model calls, over its cap of " + limits.capCalls() + ".");
            }
            UUID id = j.queryForObject("INSERT INTO experiments (workspace_id, testset_id, name, mode, question_count, stale_count, estimated_cost, created_by, question_limit, cost_cap_usd, cost_cap_calls, kind) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) RETURNING id", UUID.class,
                    ws, testsetId, cleanName, mode, loaded.questions().size(), loaded.stale(), write(estimate), p.userId(), questionLimit, verdict.effectiveCapUsd(), limits.capCalls(), kind);
            String meta = write(runMeta(mode));
            for (int i = 0; i < validated.size(); i++) {
                UUID configId = j.queryForObject("INSERT INTO pipeline_configs (workspace_id, config, config_hash) VALUES (?, ?::jsonb, ?) "
                                + "ON CONFLICT (workspace_id, config_hash) DO UPDATE SET config = EXCLUDED.config RETURNING id", UUID.class,
                        ws, write(validated.get(i)), hash(validated.get(i)));
                j.update("INSERT INTO runs (workspace_id, experiment_id, config_id, label, ordinal, meta) VALUES (?, ?, ?, ?, ?, ?::jsonb)",
                        ws, id, configId, configs.get(i).name().strip(), i, meta);
            }
            queue.enqueue(j, ws, RunExperimentHandler.TYPE, json.createObjectNode().put("experimentId", id.toString()), "experiment-" + id);
            audit(j, ws, p.actor(), "experiment.create", "experiment:" + id);
            return view(j, ws, id, false);
        });
    }

    /** What a run needs to be reproduced or questioned later (SPEC 7: model names, prompt versions and seeds are stored with each run). */
    private Map<String, Object> runMeta(String mode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode);
        if (mode.equals("FULL")) {
            m.put("generationModel", llm.model());
            m.put("judgeModel", llm.model());
            m.put("rubricVersion", Judge.RUBRIC_VERSION);
            m.put("answerPromptVersion", PromptBuilder.VERSION);
            m.put("nliModel", nli.model());
            m.put("temperature", 0.0);
            m.put("seed", seed);
            m.put("maxTokens", maxTokens);
        }
        m.put("embeddingModel", embeddingModel);
        return m;
    }

    // ------------------------------------------------------------------ reading

    public List<ExperimentView> list(Principal p, UUID ws) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            List<UUID> ids = j.queryForList("SELECT id FROM experiments WHERE kind <> 'AUTOTUNE' ORDER BY created_at DESC, id DESC LIMIT 100", UUID.class);
            List<ExperimentView> out = new ArrayList<>();
            for (UUID id : ids) out.add(view(j, ws, id, false));
            return out;
        });
    }

    public ExperimentView get(Principal p, UUID ws, UUID id) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            return view(j, ws, id, true);
        });
    }

    public List<ResultView> results(Principal p, UUID ws, UUID experimentId, UUID runId) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            if (j.queryForList("SELECT 1 FROM runs WHERE id = ? AND experiment_id = ?", runId, experimentId).isEmpty()) throw ApiException.notFound("No such run");
            return j.query("SELECT r.question_id, q.question, q.qtype, r.metrics::text AS metrics, r.latency_ms, r.failure_stage, r.retrieved::text AS retrieved, "
                            + "r.final_context::text AS final_context, r.answer, r.claims::text AS claims, r.trace_id FROM run_results r JOIN test_questions q ON q.id = r.question_id "
                            + "WHERE r.run_id = ? ORDER BY q.seq", (rs, n) -> new ResultView(rs.getObject("question_id", UUID.class), rs.getString("question"),
                            rs.getString("qtype"), read(rs.getString("metrics"), METRICS), (Integer) rs.getObject("latency_ms"), rs.getString("failure_stage"),
                            readTree(rs.getString("retrieved")), readTree(rs.getString("final_context")), rs.getString("answer"),
                            rs.getString("claims") == null ? null : readTree(rs.getString("claims")), rs.getObject("trace_id", UUID.class)), runId);
        });
    }

    public void cancel(Principal p, UUID ws, UUID id) {
        tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.EDITOR);
            var rows = j.queryForList("SELECT status FROM experiments WHERE id = ? FOR UPDATE", String.class, id);
            if (rows.isEmpty()) throw ApiException.notFound("No such experiment");
            if (!rows.get(0).equals("QUEUED") && !rows.get(0).equals("RUNNING")) throw ApiException.conflict("This experiment has already " + rows.get(0).toLowerCase());
            j.update("UPDATE experiments SET status = 'CANCELLED', finished_at = now() WHERE id = ?", id);
            j.update("UPDATE runs SET status = 'CANCELLED' WHERE experiment_id = ? AND status <> 'DONE'", id);
            audit(j, ws, p.actor(), "experiment.cancel", "experiment:" + id);
            return null;
        });
    }

    /** Two runs of one test set compared on the questions both measured. */
    public ComparisonView compare(Principal p, UUID ws, UUID runA, UUID runB) {
        return tx.workspace(ws, p.userId(), j -> {
            require(j, p, ws, Role.VIEWER);
            var a = runInfo(j, runA);
            var b = runInfo(j, runB);
            if (!a.testsetId().equals(b.testsetId())) throw ApiException.conflict("These runs measured different test sets, so they cannot be compared question by question");
            if (!a.status().equals("DONE") || !b.status().equals("DONE")) throw ApiException.conflict("Both runs must be finished before they are compared");
            Map<UUID, Map<String, Double>> va = scores(j, runA), vb = scores(j, runB);
            List<UUID> common = new ArrayList<>(va.keySet());
            common.sort(null);
            common.retainAll(vb.keySet());
            Aligned al = align(va, vb, common);
            List<Comparisons.Row> rows = Comparisons.compare(al.a(), al.b(), runA + ":" + runB);

            // which questions changed sides: by whether the context held the evidence (retrieval) or whether the answer was fully correct (full runs)
            boolean full = a.mode().equals("FULL") && b.mode().equals("FULL");
            String flipMetric = full ? Metrics.CORRECTNESS : RetrievalMetrics.CONTEXT_PASS;
            List<Flip> passToFail = new ArrayList<>(), failToPass = new ArrayList<>();
            Map<UUID, String[]> text = questionText(j, common);
            Map<UUID, UUID> traceA = traceIds(j, runA), traceB = traceIds(j, runB);
            for (UUID q : common) {
                Double x = va.get(q).get(flipMetric), y = vb.get(q).get(flipMetric);
                if (x == null || y == null) continue;
                boolean passA = full ? x >= 1.0 : x > 0.5, passB = full ? y >= 1.0 : y > 0.5;
                if (passA == passB) continue;
                Flip flip = new Flip(q, text.get(q)[0], text.get(q)[1], x, y, traceA.get(q), traceB.get(q));
                if (passA) passToFail.add(flip);
                else failToPass.add(flip);
            }
            return new ComparisonView(runA, runB, a.label(), b.label(), common.size(), rows, flipMetric, passToFail, failToPass);
        });
    }

    // ------------------------------------------------------------------ shared with the runner

    record RunInfo(UUID id, UUID experimentId, UUID testsetId, String label, String status, String mode) {}

    private RunInfo runInfo(JdbcTemplate j, UUID runId) {
        var rows = j.query("SELECT r.id, r.experiment_id, e.testset_id, r.label, r.status, e.mode FROM runs r JOIN experiments e ON e.id = r.experiment_id WHERE r.id = ?",
                (rs, n) -> new RunInfo(rs.getObject("id", UUID.class), rs.getObject("experiment_id", UUID.class), rs.getObject("testset_id", UUID.class),
                        rs.getString("label"), rs.getString("status"), rs.getString("mode")), runId);
        if (rows.isEmpty()) throw ApiException.notFound("No such run");
        return rows.get(0);
    }

    /** Per-question scores of a run, by question id. */
    Map<UUID, Map<String, Double>> scores(JdbcTemplate j, UUID runId) {
        Map<UUID, Map<String, Double>> out = new LinkedHashMap<>();
        j.query("SELECT question_id, metrics::text AS metrics FROM run_results WHERE run_id = ?",
                rs -> { out.put(rs.getObject("question_id", UUID.class), read(rs.getString("metrics"), METRICS)); }, runId);
        return out;
    }

    record Aligned(Map<String, double[]> a, Map<String, double[]> b) {}

    /** Score vectors of two runs, one metric at a time, over the questions where both runs have that metric (faithfulness is undefined for an abstention). */
    static Aligned align(Map<UUID, Map<String, Double>> a, Map<UUID, Map<String, Double>> b, List<UUID> questions) {
        Map<String, double[]> x = new LinkedHashMap<>(), y = new LinkedHashMap<>();
        for (String metric : Metrics.ALL) {
            List<double[]> pairs = new ArrayList<>();
            for (UUID q : questions) {
                Double da = a.get(q).get(metric), db = b.get(q).get(metric);
                if (da != null && db != null) pairs.add(new double[] {da, db});
            }
            if (pairs.isEmpty()) continue;
            double[] va = new double[pairs.size()], vb = new double[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) {
                va[i] = pairs.get(i)[0];
                vb[i] = pairs.get(i)[1];
            }
            x.put(metric, va);
            y.put(metric, vb);
        }
        return new Aligned(x, y);
    }

    private Map<UUID, UUID> traceIds(JdbcTemplate j, UUID runId) {
        Map<UUID, UUID> out = new LinkedHashMap<>();
        j.query("SELECT question_id, trace_id FROM run_results WHERE run_id = ? AND trace_id IS NOT NULL",
                rs -> { out.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)); }, runId);
        return out;
    }

    private Map<UUID, String[]> questionText(JdbcTemplate j, List<UUID> ids) {
        Map<UUID, String[]> out = new LinkedHashMap<>();
        if (ids.isEmpty()) return out;
        j.query("SELECT id, question, qtype FROM test_questions WHERE id = ANY (?)", ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids.toArray())),
                rs -> { out.put(rs.getObject("id", UUID.class), new String[] {rs.getString("question"), rs.getString("qtype")}); });
        return out;
    }

    private String testsetStatus(JdbcTemplate j, UUID testsetId) {
        var rows = j.queryForList("SELECT status FROM testsets WHERE id = ?", String.class, testsetId);
        if (rows.isEmpty()) throw ApiException.notFound("No such test set");
        return rows.get(0);
    }

    private ExperimentView view(JdbcTemplate j, UUID ws, UUID id, boolean withComparisons) {
        var rows = j.query("SELECT e.id, e.testset_id, t.name AS testset_name, t.version AS testset_version, e.name, e.status, e.kind, e.mode, e.question_count, "
                        + "e.stale_count, e.estimated_cost::text AS estimated, e.actual_cost::text AS actual, e.error, e.created_at, e.finished_at "
                        + "FROM experiments e JOIN testsets t ON t.id = e.testset_id WHERE e.id = ?",
                (rs, n) -> new Object[] {rs.getObject("testset_id", UUID.class), rs.getString("testset_name"), rs.getInt("testset_version"), rs.getString("name"),
                        rs.getString("status"), rs.getString("kind"), rs.getString("mode"), rs.getInt("question_count"), rs.getInt("stale_count"),
                        read(rs.getString("estimated"), OBJECT_MAP), read(rs.getString("actual"), OBJECT_MAP), rs.getString("error"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class)}, id);
        if (rows.isEmpty()) throw ApiException.notFound("No such experiment");
        Object[] r = rows.get(0);

        List<RunView> runs = new ArrayList<>();
        var runRows = j.queryForList("SELECT r.id, r.label, r.ordinal, r.status, r.samples_done, c.config::text AS config FROM runs r JOIN pipeline_configs c ON c.id = r.config_id "
                + "WHERE r.experiment_id = ? ORDER BY r.ordinal", id);
        for (var row : runRows) {
            UUID runId = (UUID) row.get("id");
            List<MetricView> metrics = j.query("SELECT metric, slice, method, value, ci_low, ci_high, n FROM run_metrics WHERE run_id = ? ORDER BY metric, slice, method",
                    (rs, n) -> new MetricView(rs.getString("metric"), rs.getString("slice"), rs.getString("method"), finite(rs.getObject("value")), finite(rs.getObject("ci_low")),
                            finite(rs.getObject("ci_high")), rs.getInt("n")), runId);
            PipelineConfig config;
            try {
                config = json.readValue((String) row.get("config"), PipelineConfig.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("stored pipeline config is unreadable", e);
            }
            runs.add(new RunView(runId, (String) row.get("label"), (Integer) row.get("ordinal"), (String) row.get("status"), (Integer) row.get("samples_done"), config, metrics));
        }

        List<RunComparison> vsBaseline = withComparisons ? baselineComparisons(j, runs) : List.of();
        String mode = (String) r[6];
        @SuppressWarnings("unchecked")
        ExperimentView view = new ExperimentView(id, (UUID) r[0], (String) r[1], (Integer) r[2], (String) r[3], (String) r[4], (String) r[5], mode, (Integer) r[7],
                (Integer) r[8], (Map<String, Object>) r[9], (Map<String, Object>) r[10], (String) r[11], (OffsetDateTime) r[12], (OffsetDateTime) r[13], runs, vsBaseline,
                mode.equals("FULL") ? judgeInfo(j, ws) : null);
        return view;
    }

    /**
     * Whether the judge behind the judged metrics can be trusted: calibrated (kappa at least 0.6 against human labels for this judge model and
     * rubric version), uncalibrated (measured and below that), or unknown (never calibrated). Also warns that the judge is the generator.
     */
    JudgeInfo judgeInfo(JdbcTemplate j, UUID ws) {
        Map<String, JudgeMetric> all = calibration.statuses(j), metrics = new LinkedHashMap<>();
        for (String metric : List.of(Metrics.FAITHFULNESS, Metrics.CORRECTNESS, Metrics.RELEVANCE)) metrics.put(metric, all.get(metric));
        List<String> warnings = new ArrayList<>();
        warnings.add("The model that writes the answers (" + llm.model() + ") also judges them. Models tend to favour their own output, so treat judged scores with care.");
        return new JudgeInfo(llm.model(), Judge.RUBRIC_VERSION, llm.model(), nli.model(), true, metrics, warnings);
    }

    /** Every finished run against the baseline (the first run), with one Holm correction over the configurations compared, metric by metric. */
    List<RunComparison> baselineComparisons(JdbcTemplate j, List<RunView> runs) {
        if (runs.size() < 2 || !runs.get(0).status().equals("DONE")) return List.of();
        Map<UUID, Map<String, Double>> base = scores(j, runs.get(0).id());
        List<Comparisons.Pair> pairs = new ArrayList<>();
        List<RunView> compared = new ArrayList<>();
        for (RunView run : runs.subList(1, runs.size())) {
            if (!run.status().equals("DONE")) continue;
            Map<UUID, Map<String, Double>> other = scores(j, run.id());
            List<UUID> common = new ArrayList<>(base.keySet());
            common.sort(null);
            common.retainAll(other.keySet());
            Aligned al = align(base, other, common);
            if (al.a().isEmpty()) continue;
            pairs.add(new Comparisons.Pair(run.id().toString(), al.a(), al.b()));
            compared.add(run);
        }
        Map<String, List<Comparisons.Row>> family = Comparisons.compareFamily(pairs);
        List<RunComparison> out = new ArrayList<>();
        for (RunView run : compared) out.add(new RunComparison(run.id(), run.label(), family.get(run.id().toString())));
        return out;
    }

    private static Double finite(Object o) {
        if (o == null) return null;
        double d = ((Number) o).doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(String text, TypeReference<T> type) {
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored value is unreadable", e);
        }
    }

    private Object readTree(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored value is unreadable", e);
        }
    }
}
