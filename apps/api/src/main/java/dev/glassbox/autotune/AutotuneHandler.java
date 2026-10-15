package dev.glassbox.autotune;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.autotune.AutotuneModel.Options;
import dev.glassbox.autotune.SearchSpace.Candidate;
import dev.glassbox.cost.BudgetService;
import dev.glassbox.embed.EmbeddingProviders;
import dev.glassbox.eval.ExperimentService;
import dev.glassbox.eval.MeasurableQuestions;
import dev.glassbox.eval.MeasurableQuestions.Question;
import dev.glassbox.eval.Metrics;
import dev.glassbox.eval.QuestionEvaluator;
import dev.glassbox.eval.RetrievalMetrics;
import dev.glassbox.eval.RetrievalMetrics.Gold;
import dev.glassbox.eval.RetrievalMetrics.Ref;
import dev.glassbox.index.IndexService;
import dev.glassbox.jobs.Job;
import dev.glassbox.jobs.JobHandler;
import dev.glassbox.jobs.JobQueue;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalPipeline;
import dev.glassbox.retrieval.Span;
import dev.glassbox.stats.Bootstrap;
import dev.glassbox.stats.Holm;
import dev.glassbox.stats.PairedTests;
import dev.glassbox.stats.Power;
import dev.glassbox.stats.Summary;
import dev.glassbox.stats.Verdicts;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs an AutoTune search (SPEC 9). Stage 1 chooses a chunking and embedding model, stage 2 the retrieval settings, both by successive
 * halving on the tuning questions and without any model call. Stage 3 (optional) measures the finalists and the baseline with full answers on
 * the tuning questions. Stage 4 measures the finalists and the baseline on the held-out questions, which no earlier choice has seen, and the
 * headline numbers come from there only.
 *
 * Everything is resumable. Per-question scores of stages 1 and 2 are stored with each candidate, runs of stages 3 and 4 are ordinary experiment
 * runs whose results are unique per (run, question), and the halving is deterministic, so a restarted job asks for scores it already has.
 */
@Component
public class AutotuneHandler implements JobHandler {
    public static final String TYPE = "AUTOTUNE";
    private static final Logger log = LoggerFactory.getLogger(AutotuneHandler.class);
    private static final int LLM_CALLS_PER_QUESTION = 5;
    private static final int TUNING_RUN_ORDINAL = 100;
    private static final TypeReference<Map<String, double[]>> SCORES = new TypeReference<>() {};

    private final Tx tx;
    private final ObjectMapper json;
    private final JobQueue queue;
    private final RetrievalPipeline pipeline;
    private final IndexService indexes;
    private final QuestionEvaluator evaluator;
    private final ExperimentService experiments;
    private final EmbeddingProviders providers;
    private final AutotuneService service;
    private final BudgetService budgets;

    public AutotuneHandler(Tx tx, ObjectMapper json, JobQueue queue, RetrievalPipeline pipeline, IndexService indexes, QuestionEvaluator evaluator,
                           ExperimentService experiments, EmbeddingProviders providers, AutotuneService service, BudgetService budgets) {
        this.budgets = budgets;
        this.tx = tx;
        this.json = json;
        this.queue = queue;
        this.pipeline = pipeline;
        this.indexes = indexes;
        this.evaluator = evaluator;
        this.experiments = experiments;
        this.providers = providers;
        this.service = service;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** The spend cap was reached: the search stops and keeps the best result so far. */
    private static final class CapReached extends RuntimeException {
        CapReached() {
            super("spend cap reached", null, false, false);
        }
    }

    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelled", null, false, false);
        }
    }

    @Override
    public void handle(Job job) throws Exception {
        UUID ws = job.workspaceId();
        UUID id = UUID.fromString(job.payload().get("autotuneId").asText());
        var rows = tx.workspace(ws, null, j -> j.queryForList("SELECT status, testset_id, name, options::text AS options, spent::text AS spent, experiment_id FROM autotunes WHERE id = ?", id));
        if (rows.isEmpty()) return;
        var row = rows.get(0);
        if (!row.get("status").equals("QUEUED") && !row.get("status").equals("RUNNING")) return;
        tx.workspace(ws, null, j -> j.update("UPDATE autotunes SET status = 'RUNNING' WHERE id = ? AND status = 'QUEUED'", id));
        Options o = json.readValue((String) row.get("options"), Options.class);
        Map<String, Object> spentBefore = json.readValue((String) row.get("spent"), new TypeReference<>() {});
        Search search = new Search(job, ws, id, (UUID) row.get("testset_id"), (String) row.get("name"), o, (UUID) row.get("experiment_id"), spentBefore);
        try {
            search.run();
        } catch (Cancelled c) {
            log.info("autotune {} cancelled", id);
        }
    }

    @Override
    public void onDead(Job job, String error) {
        UUID id = UUID.fromString(job.payload().get("autotuneId").asText());
        tx.workspace(job.workspaceId(), null, j -> {
            j.update("UPDATE autotunes SET status = 'FAILED', error = ?, finished_at = now() WHERE id = ? AND status IN ('QUEUED', 'RUNNING')",
                    "Stopped: " + (error == null ? "unknown error" : error), id);
            j.update("UPDATE experiments SET status = 'FAILED', finished_at = now() WHERE id = (SELECT experiment_id FROM autotunes WHERE id = ?) AND status = 'RUNNING'", id);
            return null;
        });
    }

    // ================================================================== one search

    /** A candidate of stage 1 or 2 with the scores it has so far: question id to [context recall, latency ms, prompt tokens]. */
    private static final class Scored {
        final UUID rowId;
        final Candidate def;
        final Map<String, double[]> scores;

        Scored(UUID rowId, Candidate def, Map<String, double[]> scores) {
            this.rowId = rowId;
            this.def = def;
            this.scores = scores;
        }
    }

    /** What one measured configuration looked like on a set of questions. */
    private record RunScores(String key, String label, PipelineConfig config, Map<String, String> settings, UUID runId, Map<UUID, Double> quality,
                             double meanTokens, double medianLatencyMs, double meanContextRecall, Double meanCorrectness, Double meanFaithfulness) {
        double mean() {
            return quality.values().stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
        }
    }

    private final class Search {
        final Job job;
        final UUID ws, id, testsetId;
        final String name;
        final Options o;
        UUID experimentId;
        final long startedAt = System.currentTimeMillis();
        final long elapsedBefore;
        int llmCalls, retrievalCalls;
        boolean capped;
        final Runnable alive;

        Search(Job job, UUID ws, UUID id, UUID testsetId, String name, Options o, UUID experimentId, Map<String, Object> spentBefore) {
            this.job = job;
            this.ws = ws;
            this.id = id;
            this.testsetId = testsetId;
            this.name = name;
            this.o = o;
            this.experimentId = experimentId;
            this.elapsedBefore = ((Number) spentBefore.getOrDefault("elapsedMs", 0)).longValue();
            this.llmCalls = ((Number) spentBefore.getOrDefault("llmCalls", 0)).intValue();
            this.retrievalCalls = ((Number) spentBefore.getOrDefault("retrievalCalls", 0)).intValue();
            this.alive = () -> queue.heartbeat(job);
        }

        long elapsedMs() {
            return elapsedBefore + System.currentTimeMillis() - startedAt;
        }

        boolean capReached() {
            return elapsedMs() >= o.capMinutes() * 60_000L || llmCalls >= o.capLlmCalls() || retrievalCalls >= o.capSearches();
        }

        void checkpoint(String stage) {
            Map<String, Object> spent = new LinkedHashMap<>();
            spent.put("elapsedMs", elapsedMs());
            spent.put("llmCalls", llmCalls);
            spent.put("retrievalCalls", retrievalCalls);
            String text = write(spent);
            tx.workspace(ws, null, j -> {
                int n = j.update("UPDATE autotunes SET stage = coalesce(?, stage), spent = ?::jsonb WHERE id = ? AND status = 'RUNNING'", stage, text, id);
                if (n == 0) throw new Cancelled();
                return null;
            });
        }

        void run() throws Exception {
            MeasurableQuestions.Loaded all = tx.workspace(ws, null, j -> MeasurableQuestions.load(j, json, testsetId, true, null));
            Splits.Result split = Splits.split(o.seed(), all.questions().stream().map(q -> new Splits.Item(q.id(), q.qtype())).toList());
            List<Question> tuningAll = all.questions().stream().filter(q -> !split.isHeldOut(q.id())).toList();
            List<Question> heldAll = all.questions().stream().filter(q -> split.isHeldOut(q.id())).toList();
            List<Question> tuningOrdered = MeasurableQuestions.hashOrdered(tuningAll.stream().filter(q -> !q.spans().isEmpty()).toList());
            List<Question> heldRetrieval = heldAll.stream().filter(q -> !q.spans().isEmpty()).toList();
            ensureExperiment(all.questions().size());

            String baselineModel = providers.defaultModel();
            PipelineConfig baseline = SearchSpace.baseline(baselineModel);
            Candidate baselineCandidate = SearchSpace.candidate(baseline);

            // ---------------------------------------------------------------- stage 1: chunking and embedding model
            checkpoint("INDEXING");
            List<Candidate> s1defs = SearchSpace.stage1(o.seed(), o.stage1Candidates(), service.models(), baselineModel);
            Map<String, Scored> s1 = loadCandidates("STAGE1", s1defs);
            for (IndexService.Need need : indexes.needs(s1defs.stream().map(Candidate::config).toList())) {
                if (cancelled()) throw new Cancelled();
                indexes.ensure(ws, need, alive);
            }
            checkpoint("STAGE1");
            Ranking r1 = halve("STAGE1", s1, tuningOrdered);
            Scored winner1 = r1.ranked().get(0);

            // ---------------------------------------------------------------- stage 2: retrieval settings
            checkpoint("STAGE2");
            Ranking r2;
            Map<String, Scored> s2 = Map.of();
            if (capped) {
                r2 = r1;
            } else {
                s2 = loadCandidates("STAGE2", SearchSpace.stage2(o.seed() + 1, o.stage2Candidates(), winner1.def.config(), o.transformers()));
                r2 = halve("STAGE2", s2, tuningOrdered);
            }
            List<Scored> finalists = r2.ranked().subList(0, Math.min(o.finalists(), r2.ranked().size()));

            // the baseline is always measured; its tuning score is needed for the "tuning versus held-out" comparison
            Scored baselineScored = s1.get(baselineCandidate.key());
            try {
                if (baselineScored != null && !capped) ensureScored("STAGE1", baselineScored, tuningOrdered, tuningOrdered.size());
            } catch (CapReached e) {
                capped = true;
            }

            // ---------------------------------------------------------------- stage 3: full answers on the tuning split (optional)
            boolean full = o.fullStage() && !capped;
            Map<String, RunScores> tuningRuns = new LinkedHashMap<>();
            List<Question> fullTuning = MeasurableQuestions.hashOrdered(tuningAll).stream().limit(o.fullQuestions()).toList();
            List<Question> fullHeld = MeasurableQuestions.hashOrdered(heldAll).stream().limit(o.fullQuestions()).toList();
            List<Candidate> contenders = new ArrayList<>();
            for (Scored f : finalists) contenders.add(f.def);
            if (contenders.stream().noneMatch(c -> c.key().equals(baselineCandidate.key()))) contenders.add(baselineCandidate);
            if (full) {
                checkpoint("STAGE3");
                try {
                    int ordinal = TUNING_RUN_ORDINAL;
                    for (Candidate c : contenders) {
                        tuningRuns.put(c.key(), measure(c, c.label() + " · tuning", ordinal++, fullTuning, true, "TUNING"));
                    }
                } catch (CapReached e) {
                    capped = true;
                    full = false;
                    tuningRuns.clear();
                }
            }

            // The winner is chosen from tuning results only.
            Map<String, Scored> byKey = new HashMap<>();
            r2.ranked().forEach(s -> byKey.put(s.def.key(), s));
            Objective.Score[] ranked = finalists.stream().map(f -> {
                RunScores t = tuningRuns.get(f.def.key());
                return t != null ? new Objective.Score(f.def.key(), t.mean(), t.meanTokens(), t.medianLatencyMs()) : retrievalScore(f, tuningOrdered.size());
            }).sorted(o.objective().comparator(o.qualityFloor(), o.latencyCapMs())).toArray(Objective.Score[]::new);
            String winnerKey = ranked[0].id();

            // ---------------------------------------------------------------- stage 4: held-out confirmation
            checkpoint("STAGE4");
            Map<String, RunScores> heldRuns = new LinkedHashMap<>();
            int ordinal = 0;
            List<Candidate> ordered = new ArrayList<>();
            ordered.add(baselineCandidate);
            for (Candidate c : contenders) if (!c.key().equals(baselineCandidate.key())) ordered.add(c);
            for (Candidate c : ordered) {
                heldRuns.put(c.key(), measure(c, c.label() + " · held-out", ordinal++, full ? fullHeld : heldRetrieval, full, "HELD_OUT"));
            }

            Map<String, Object> result = outcome(r1, r2, s1, s2, finalists, baselineCandidate, winnerKey, ranked, tuningRuns, heldRuns, tuningOrdered.size(), full,
                    full ? fullHeld.size() : heldRetrieval.size(), baselineScored);
            finishRun(result);
        }

        // ------------------------------------------------------------ stages 1 and 2

        record Ranking(List<Scored> ranked, SuccessiveHalving.Result halving) {}

        Ranking halve(String stage, Map<String, Scored> cands, List<Question> ordered) {
            if (ordered.isEmpty() || cands.isEmpty()) throw new IllegalStateException("nothing to tune on");
            List<String> keys = new ArrayList<>(cands.keySet());
            try {
                SuccessiveHalving.Result r = SuccessiveHalving.run(keys, ordered.size(), 4, stage.equals("STAGE1") ? Math.max(3, o.finalists()) : o.finalists(),
                        (key, size) -> {
                            Scored c = cands.get(key);
                            ensureScored(stage, c, ordered, size);
                            return meanRecall(c, ordered, size);
                        }, java.util.Comparator.naturalOrder());
                List<Scored> ranked = new ArrayList<>(r.ranking().stream().map(cands::get).toList());
                ranked.sort(java.util.Comparator.comparing((Scored s) -> retrievalScore(s, ordered.size()), o.objective().comparator(o.qualityFloor(), o.latencyCapMs())
                        .thenComparing(Objective.Score::id)) .thenComparing(s -> s.def.key()));
                markFinalSample(stage, cands, ordered.size(), r);
                return new Ranking(ranked, r);
            } catch (CapReached e) {
                capped = true;
                return bestSoFar(cands, ordered);
            }
        }

        /** The cap hit part-way: rank what has been scored, comparing the candidates on the questions they all have. */
        Ranking bestSoFar(Map<String, Scored> cands, List<Question> ordered) {
            List<Scored> scored = cands.values().stream().filter(c -> !c.scores.isEmpty()).collect(Collectors.toList());
            if (scored.isEmpty()) scored = new ArrayList<>(cands.values().stream().limit(1).toList());
            int common = Math.max(1, scored.stream().mapToInt(c -> c.scores.size()).min().orElse(1));
            List<Scored> usable = scored.stream().filter(c -> c.scores.size() >= Math.min(common, ordered.size())).collect(Collectors.toList());
            int size = Math.min(common, ordered.size());
            usable.sort(java.util.Comparator.comparing((Scored s) -> retrievalScore(s, size), o.objective().comparator(o.qualityFloor(), o.latencyCapMs())
                    .thenComparing(Objective.Score::id)).thenComparing(s -> s.def.key()));
            return new Ranking(usable, new SuccessiveHalving.Result(usable.stream().map(s -> s.def.key()).toList(), List.of()));
        }

        Objective.Score retrievalScore(Scored c, int size) {
            List<double[]> have = new ArrayList<>(c.scores.values());
            if (have.isEmpty()) return new Objective.Score(c.def.key(), 0, 0, 0);
            double recall = have.stream().mapToDouble(a -> a[0]).average().orElse(0), tokens = have.stream().mapToDouble(a -> a[2]).average().orElse(0);
            double latency = Summary.percentile(have.stream().mapToDouble(a -> a[1]).toArray(), 50);
            return new Objective.Score(c.def.key(), recall, tokens, latency);
        }

        double meanRecall(Scored c, List<Question> ordered, int size) {
            double sum = 0;
            for (int i = 0; i < size; i++) sum += c.scores.get(ordered.get(i).id().toString())[0];
            return sum / size;
        }

        /** Scores the first {@code size} questions of the fixed order that this candidate has no score for yet. */
        void ensureScored(String stage, Scored c, List<Question> ordered, int size) {
            boolean changed = false;
            for (int i = 0; i < size; i++) {
                Question q = ordered.get(i);
                String key = q.id().toString();
                if (c.scores.containsKey(key)) continue;
                if (capReached()) {
                    if (changed) saveScores(c);
                    throw new CapReached();
                }
                if (changed && i % 8 == 0 && cancelled()) throw new Cancelled();
                alive.run();
                long t0 = System.nanoTime();
                RetrievalPipeline.Result r = pipeline.retrieve(ws, q.text(), c.def.config());
                List<Gold> gold = q.spans().stream().map(s -> new Gold(s.documentId(), s.charStart(), s.charEnd())).toList();
                double recall = RetrievalMetrics.compute(r.reranked().stream().map(AutotuneHandler::ref).toList(), r.context().included().stream().map(x -> ref(x.chunk())).toList(), gold)
                        .get(RetrievalMetrics.CONTEXT_RECALL);
                double latency = r.spans().stream().mapToLong(Span::millis).sum();
                c.scores.put(key, new double[] {recall, latency, r.context().tokensUsed()});
                retrievalCalls++;
                for (Span sp : r.spans()) if (sp.stage().equals("query_transform")) llmCalls += ((Number) sp.detail().getOrDefault("llmCalls", 0)).intValue();
                changed = true;
            }
            if (changed) saveScores(c);
        }

        void saveScores(Scored c) {
            String text = write(c.scores);
            tx.workspace(ws, null, j -> j.update("UPDATE autotune_candidates SET scores = ?::jsonb WHERE id = ?", text, c.rowId));
            checkpoint(null);
        }

        void markFinalSample(String stage, Map<String, Scored> cands, int n, SuccessiveHalving.Result r) {
            tx.workspace(ws, null, j -> {
                for (String key : r.ranking()) j.update("UPDATE autotune_candidates SET final_sample = ? WHERE id = ?", n, cands.get(key).rowId);
                return null;
            });
        }

        Map<String, Scored> loadCandidates(String stage, List<Candidate> defs) {
            return tx.workspace(ws, null, j -> {
                Map<String, Scored> out = new LinkedHashMap<>();
                for (Candidate c : defs) {
                    j.update("INSERT INTO autotune_candidates (workspace_id, autotune_id, stage, key, label, config, settings) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) "
                            + "ON CONFLICT (autotune_id, stage, key) DO NOTHING", ws, id, stage, c.key(), c.label(), write(c.config()), write(c.settings()));
                    var row = j.queryForMap("SELECT id, scores::text AS scores FROM autotune_candidates WHERE autotune_id = ? AND stage = ? AND key = ?", id, stage, c.key());
                    try {
                        out.put(c.key(), new Scored((UUID) row.get("id"), c, new LinkedHashMap<>(json.readValue((String) row.get("scores"), SCORES))));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return out;
            });
        }

        boolean cancelled() {
            return !"RUNNING".equals(tx.workspace(ws, null, j -> j.queryForObject("SELECT status FROM autotunes WHERE id = ?", String.class, id)));
        }

        // ------------------------------------------------------------ stages 3 and 4

        void ensureExperiment(int questions) {
            if (experimentId != null) return;
            experimentId = tx.workspace(ws, null, j -> {
                UUID eid = j.queryForObject("INSERT INTO experiments (workspace_id, testset_id, name, mode, kind, status, question_count, created_by) "
                                + "SELECT ?, ?, ?, ?, 'AUTOTUNE', 'RUNNING', ?, created_by FROM autotunes WHERE id = ? RETURNING id", UUID.class,
                        ws, testsetId, "AutoTune: " + name, o.fullStage() ? "FULL" : "RETRIEVAL", questions, id);
                j.update("UPDATE autotunes SET experiment_id = ? WHERE id = ?", eid, id);
                return eid;
            });
        }

        /** Runs one configuration over the questions as an ordinary experiment run and reads back what it scored. */
        RunScores measure(Candidate c, String label, int ordinal, List<Question> questions, boolean full, String part) throws Exception {
            UUID runId = tx.workspace(ws, null, j -> {
                var existing = j.queryForList("SELECT id FROM runs WHERE experiment_id = ? AND label = ?", UUID.class, experimentId, label);
                if (!existing.isEmpty()) return existing.get(0);
                UUID configId = experiments.upsertConfig(j, ws, c.config());
                return j.queryForObject("INSERT INTO runs (workspace_id, experiment_id, config_id, label, ordinal, meta, status, started_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'RUNNING', now()) RETURNING id",
                        UUID.class, ws, experimentId, configId, label, ordinal, write(Map.of("mode", full ? "FULL" : "RETRIEVAL", "part", part, "autotune", id.toString())));
            });
            Set<UUID> done = new HashSet<>(tx.workspace(ws, null, j -> j.queryForList("SELECT question_id FROM run_results WHERE run_id = ?", UUID.class, runId)));
            for (Question q : questions) {
                if (done.contains(q.id())) continue;
                if (cancelled()) throw new Cancelled();
                if (full && capReached()) throw new CapReached();
                alive.run();
                evaluator.evaluate(ws, runId, q, c.config(), full, alive);
                if (full) llmCalls += LLM_CALLS_PER_QUESTION; else retrievalCalls++;
                checkpoint(null);
            }
            evaluator.finishRun(ws, runId, new MeasurableQuestions.Loaded(questions, 0, 0));
            return readRun(c, label, runId, questions);
        }

        RunScores readRun(Candidate c, String label, UUID runId, List<Question> questions) {
            Map<UUID, Double> quality = new LinkedHashMap<>();
            List<Double> tokens = new ArrayList<>(), latencies = new ArrayList<>(), recalls = new ArrayList<>(), corrects = new ArrayList<>(), faiths = new ArrayList<>();
            tx.workspace(ws, null, j -> {
                j.query("SELECT question_id, metrics::text AS metrics, latency_ms, (failure_detail->>'contextTokens') AS tokens FROM run_results WHERE run_id = ? ORDER BY question_id", rs -> {
                    try {
                        Map<String, Double> m = json.readValue(rs.getString("metrics"), new TypeReference<Map<String, Double>>() {});
                        Double recall = m.get(RetrievalMetrics.CONTEXT_RECALL), correct = m.get(Metrics.CORRECTNESS), faith = m.get(Metrics.FAITHFULNESS);
                        quality.put(rs.getObject("question_id", UUID.class), Quality.blend(recall, correct, faith));
                        if (recall != null) recalls.add(recall);
                        if (correct != null) corrects.add(correct);
                        if (faith != null) faiths.add(faith);
                        latencies.add((double) rs.getInt("latency_ms"));
                        if (rs.getString("tokens") != null) tokens.add(Double.parseDouble(rs.getString("tokens")));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, runId);
                return null;
            });
            return new RunScores(c.key(), label, c.config(), c.settings(), runId, quality, avg(tokens), latencies.isEmpty() ? 0 : Summary.percentile(toArray(latencies), 50),
                    avg(recalls), corrects.isEmpty() ? null : avg(corrects), faiths.isEmpty() ? null : avg(faiths));
        }

        // ------------------------------------------------------------ the report

        Map<String, Object> outcome(Ranking r1, Ranking r2, Map<String, Scored> s1, Map<String, Scored> s2, List<Scored> finalists, Candidate baseline, String winnerKey,
                                    Objective.Score[] tuningOrder, Map<String, RunScores> tuningRuns, Map<String, RunScores> heldRuns, int tuningN, boolean full, int heldN,
                                    Scored baselineScored) {
            RunScores base = heldRuns.get(baseline.key());
            List<String> challengers = heldRuns.keySet().stream().filter(k -> !k.equals(baseline.key())).toList();

            // paired comparison of quality, finalist against baseline, on the held-out questions both measured; Holm across the finalists
            Map<String, double[]> diffs = new LinkedHashMap<>();
            double[] p = new double[challengers.size()];
            Map<String, Bootstrap.Interval> cis = new LinkedHashMap<>();
            for (int i = 0; i < challengers.size(); i++) {
                RunScores other = heldRuns.get(challengers.get(i));
                List<UUID> common = base.quality().keySet().stream().filter(other.quality()::containsKey).sorted().toList();
                double[] d = new double[common.size()];
                for (int k = 0; k < d.length; k++) d[k] = other.quality().get(common.get(k)) - base.quality().get(common.get(k));
                diffs.put(challengers.get(i), d);
                cis.put(challengers.get(i), d.length > 0 ? Bootstrap.meanCi(d, o.seed() + challengers.get(i).hashCode()) : new Bootstrap.Interval(0, 0, 0));
                p[i] = d.length > 0 ? PairedTests.signFlipP(d, o.seed() + 31L * challengers.get(i).hashCode()) : 1.0;
            }
            double[] adj = p.length == 0 ? new double[0] : Holm.adjust(p);

            List<Map<String, Object>> rows = new ArrayList<>();
            List<Pareto.Point> points = new ArrayList<>();
            for (String key : heldRuns.keySet()) {
                RunScores h = heldRuns.get(key);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", key);
                row.put("label", h.label().replace(" · held-out", ""));
                row.put("config", h.config());
                row.put("settings", h.settings());
                row.put("role", key.equals(baseline.key()) ? "BASELINE" : key.equals(winnerKey) ? "WINNER" : "FINALIST");
                row.put("heldOutRunId", h.runId());
                RunScores t = tuningRuns.get(key);
                if (t != null) {
                    row.put("tuningRunId", t.runId());
                    row.put("tuning", Map.of("quality", t.mean(), "n", t.quality().size()));
                } else {
                    Scored s = key.equals(baseline.key()) ? baselineScored : finalists.stream().filter(f -> f.def.key().equals(key)).findFirst().orElse(null);
                    if (s != null && !s.scores.isEmpty()) row.put("tuning", Map.of("quality", retrievalScore(s, tuningN).quality(), "n", s.scores.size()));
                }
                double[] q = h.quality().values().stream().mapToDouble(Double::doubleValue).toArray();
                Bootstrap.Interval ci = q.length > 0 ? Bootstrap.meanCi(q, o.seed() + key.hashCode()) : new Bootstrap.Interval(Double.NaN, Double.NaN, Double.NaN);
                Map<String, Object> held = new LinkedHashMap<>();
                held.put("quality", ci.mean());
                held.put("ciLow", ci.low());
                held.put("ciHigh", ci.high());
                held.put("n", q.length);
                held.put("contextRecall", h.meanContextRecall());
                if (h.meanCorrectness() != null) held.put("correctness", h.meanCorrectness());
                if (h.meanFaithfulness() != null) held.put("faithfulness", h.meanFaithfulness());
                held.put("promptTokens", h.meanTokens());
                held.put("latencyMs", h.medianLatencyMs());
                row.put("heldOut", held);
                int idx = challengers.indexOf(key);
                if (idx >= 0) {
                    Bootstrap.Interval d = cis.get(key);
                    Verdicts.Verdict v = Verdicts.compare(d.mean(), d.low(), d.high(), adj[idx], 100, " points");
                    Map<String, Object> vs = new LinkedHashMap<>();
                    vs.put("delta", d.mean());
                    vs.put("ciLow", d.low());
                    vs.put("ciHigh", d.high());
                    vs.put("p", p[idx]);
                    vs.put("pAdjusted", adj[idx]);
                    vs.put("outcome", v.outcome());
                    vs.put("verdict", v.text());
                    vs.put("minDetectable", diffs.get(key).length > 1 ? Power.minDetectableFromDiffs(diffs.get(key)) : null);
                    row.put("vsBaseline", vs);
                }
                rows.add(row);
                if (q.length > 0) points.add(new Pareto.Point(key, ci.mean(), h.meanTokens(), h.medianLatencyMs()));
            }
            List<Pareto.Point> frontier = Pareto.frontier(points);
            Set<String> onFrontier = frontier.stream().map(Pareto.Point::id).collect(Collectors.toSet());
            rows.forEach(r -> r.put("onFrontier", onFrontier.contains(r.get("key"))));
            Map<String, Object> picks = new LinkedHashMap<>();
            Pareto.recommend(points).ifPresent(pk -> {
                picks.put("bestQuality", pk.bestQuality());
                picks.put("bestValue", pk.bestValue());
                picks.put("fastest", pk.fastest());
            });

            Map<String, Object> winnerRow = rows.stream().filter(r -> r.get("key").equals(winnerKey)).findFirst().orElse(rows.get(0));
            Map<String, Object> headline = new LinkedHashMap<>();
            headline.put("mode", full ? "FULL" : "RETRIEVAL");
            headline.put("qualityDefinition", full ? "50% context recall + 30% correctness + 20% faithfulness (weights rescaled when a part is missing for a question)"
                    : "context recall only: answers were not generated, so correctness and faithfulness are not part of the score");
            headline.put("winnerKey", winnerKey);
            headline.put("winnerLabel", winnerRow.get("label"));
            headline.put("winnerConfig", winnerRow.get("config"));
            headline.put("baselineLabel", rows.stream().filter(r -> r.get("key").equals(baseline.key())).findFirst().map(r -> r.get("label")).orElse(null));
            headline.put("winnerIsBaseline", winnerKey.equals(baseline.key()));
            headline.put("tuning", winnerRow.get("tuning"));
            headline.put("heldOut", winnerRow.get("heldOut"));
            headline.put("baselineHeldOut", rows.get(0).get("heldOut"));
            if (winnerRow.get("vsBaseline") != null) {
                headline.put("verdict", ((Map<?, ?>) winnerRow.get("vsBaseline")).get("verdict"));
                headline.put("vsBaseline", winnerRow.get("vsBaseline"));
            } else {
                headline.put("verdict", "The baseline itself came out on top on the tuning questions; nothing was found that beats it.");
            }
            headline.put("heldOutQuestions", heldN);
            headline.put("tuningQuestions", tuningN);
            if (winnerRow.get("tuning") instanceof Map<?, ?> tn && winnerRow.get("heldOut") instanceof Map<?, ?> ho && tn.get("quality") instanceof Number a && ho.get("quality") instanceof Number b) {
                headline.put("shrinkage", a.doubleValue() - b.doubleValue());
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("headline", headline);
            result.put("candidates", rows);
            result.put("frontier", frontier.stream().map(Pareto.Point::id).toList());
            result.put("recommendations", picks);
            result.put("effects", effects(r1, s1, tuningN, "STAGE1", 0));
            ((List<Object>) result.get("effects")).addAll(effects(r2, s2, tuningN, "STAGE2", 1));
            result.put("stages", Map.of("stage1", stageSummary(r1, s1), "stage2", stageSummary(r2, s2)));
            result.put("stoppedBy", capped ? "SPEND_CAP" : null);
            return result;
        }

        @SuppressWarnings("unchecked")
        List<Object> effects(Ranking r, Map<String, Scored> cands, int n, String stage, int dummy) {
            List<Object> out = new ArrayList<>();
            if (cands.isEmpty()) return out;
            // every candidate has the first round's questions; compare them on exactly those
            int first = r.halving().rounds().isEmpty() ? cands.values().stream().mapToInt(c -> c.scores.size()).min().orElse(0) : r.halving().rounds().get(0).sample();
            Map<String, Map<String, String>> settings = new LinkedHashMap<>();
            Map<String, Double> quality = new LinkedHashMap<>();
            List<String> order = null;
            for (Scored c : cands.values()) {
                if (c.scores.size() < first || first == 0) continue;
                if (order == null) order = new ArrayList<>(c.scores.keySet()).subList(0, first);
                double sum = 0;
                int count = 0;
                for (String qid : order) {
                    double[] s = c.scores.get(qid);
                    if (s == null) continue;
                    sum += s[0];
                    count++;
                }
                if (count == 0) continue;
                settings.put(c.def.key(), c.def.settings());
                quality.put(c.def.key(), sum / count);
            }
            for (Pareto.Effect e : Pareto.effects(settings, quality)) {
                out.add(Map.of("stage", stage, "dimension", e.dimension(), "value", e.value(), "averageQuality", e.averageQuality(), "n", e.n()));
            }
            return out;
        }

        Map<String, Object> stageSummary(Ranking r, Map<String, Scored> cands) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("candidates", cands.size());
            m.put("rounds", r.halving().rounds().stream().map(x -> Map.of("sample", x.sample(), "entered", x.entered().size(), "kept", x.kept().size())).toList());
            return m;
        }

        /** Estimate against actual for the budget page: what was projected when the search started, and the model calls and tokens it really used. */
        void recordSpend() {
            try {
                String split = tx.workspace(ws, null, j -> j.queryForObject("SELECT split::text FROM autotunes WHERE id = ?", String.class, id));
                var est = json.readTree(split).path("estimate");
                double usd = tx.workspace(ws, null, j -> j.queryForObject("SELECT coalesce(sum(r.cost_usd), 0) FROM run_results r JOIN runs u ON u.id = r.run_id WHERE u.experiment_id = ?", Double.class, experimentId));
                long tokens = tx.workspace(ws, null, j -> j.queryForObject("SELECT coalesce(sum(r.tokens_in + r.tokens_out), 0) FROM run_results r JOIN runs u ON u.id = r.run_id WHERE u.experiment_id = ?", Long.class, experimentId));
                budgets.record(ws, "AUTOTUNE", id, est.path("usd").asDouble(0), usd, est.path("tokens").asLong(0), tokens, est.path("llmCalls").asInt(0), llmCalls, capped);
            } catch (Exception e) {
                log.warn("could not record the spend of autotune {}: {}", id, e.toString());
            }
        }

        void finishRun(Map<String, Object> result) {
            String text = write(result);
            String stopped = capped ? "SPEND_CAP" : null;
            tx.workspace(ws, null, j -> {
                int n = j.update("UPDATE autotunes SET status = 'DONE', stage = 'DONE', result = ?::jsonb, stopped_by = ?, finished_at = now() WHERE id = ? AND status = 'RUNNING'", text, stopped, id);
                if (n == 0) throw new Cancelled();
                j.update("UPDATE experiments SET status = 'DONE', finished_at = now() WHERE id = ?", experimentId);
                return null;
            });
            checkpoint(null);
            recordSpend();
            log.info("autotune {} done: {} retrieval calls, {} model calls, {} ms{}", id, retrievalCalls, llmCalls, elapsedMs(), capped ? " (spend cap reached)" : "");
        }
    }

    // ================================================================== helpers

    private static Ref ref(dev.glassbox.retrieval.Candidate c) {
        return new Ref(c.documentId(), c.charStart(), c.charEnd());
    }

    private static double avg(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double[] toArray(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
