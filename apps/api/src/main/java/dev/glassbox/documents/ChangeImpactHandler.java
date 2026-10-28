package dev.glassbox.documents;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.dataset.GoldSpan;
import dev.glassbox.eval.ExperimentService;
import dev.glassbox.eval.RunExperimentHandler;
import dev.glassbox.jobs.Job;
import dev.glassbox.jobs.JobHandler;
import dev.glassbox.jobs.JobQueue;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.WorkspaceDefaults;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * What to do when a document has been replaced and re-ingested (SPEC 11, T2.6):
 * <ol>
 *   <li>re-locate the gold spans of the document in the new text, into a new version of the test set that has them. A span that cannot be found marks its
 *       question STALE and pending review, and the question is left out of the new version's measurements;</li>
 *   <li>re-run the configuration that was last measured on that test set (or the workspace default, when nothing was) as a REGRESSION experiment on the
 *       new version, to be compared with the earlier run by the paired statistics of SPEC 8.</li>
 * </ol>
 * The report ({@code ImpactService}) reads the result. Idempotent per (document, version): a second delivery changes nothing.
 */
@Component
public class ChangeImpactHandler implements JobHandler {
    public static final String TYPE = "CHANGE_IMPACT";
    public static final String STALE = "STALE";
    private static final Logger log = LoggerFactory.getLogger(ChangeImpactHandler.class);

    private final Tx tx;
    private final ObjectMapper json;
    private final JobQueue queue;
    private final ExperimentService experiments;
    private final WorkspaceDefaults defaults;
    private final String embeddingModel;

    public ChangeImpactHandler(Tx tx, ObjectMapper json, JobQueue queue, ExperimentService experiments, WorkspaceDefaults defaults,
                               @Value("${glassbox.embed.default-model}") String embeddingModel) {
        this.tx = tx;
        this.json = json;
        this.queue = queue;
        this.experiments = experiments;
        this.defaults = defaults;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        UUID ws = job.workspaceId();
        UUID docId = UUID.fromString(job.payload().get("documentId").asText());
        int version = job.payload().get("version").asInt();
        tx.workspace(ws, null, j -> {
            try {
                run(j, ws, docId, version);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    @Override
    public void onDead(Job job, String error) {
        UUID docId = UUID.fromString(job.payload().get("documentId").asText());
        int version = job.payload().get("version").asInt();
        tx.workspace(job.workspaceId(), null, j -> j.update("INSERT INTO change_impacts (workspace_id, document_id, version, status, note) VALUES (?, ?, ?, 'FAILED', ?) "
                + "ON CONFLICT (document_id, version) DO UPDATE SET status = 'FAILED', note = EXCLUDED.note", job.workspaceId(), docId, version,
                "The impact could not be worked out: " + (error == null ? "unknown error" : error)));
    }

    private void run(JdbcTemplate j, UUID ws, UUID docId, int version) throws Exception {
        if (j.update("INSERT INTO change_impacts (workspace_id, document_id, version) VALUES (?, ?, ?) ON CONFLICT (document_id, version) DO NOTHING", ws, docId, version) == 0) return;
        var versions = j.queryForList("SELECT id, version, normalized_text FROM document_versions WHERE document_id = ? AND version IN (?, ?) ORDER BY version", docId, version - 1, version);
        if (versions.size() < 2 || versions.get(0).get("normalized_text") == null || versions.get(1).get("normalized_text") == null) {
            finish(j, docId, version, "SKIPPED", "The earlier version's text is not available.", null, null, null, 0, 0);
            return;
        }
        UUID newVersionId = (UUID) versions.get(1).get("id");
        String newText = (String) versions.get(1).get("normalized_text");
        String title = j.queryForObject("SELECT title FROM documents WHERE id = ?", String.class, docId);

        // the most recently finalized version of a test set that has questions about this document
        var sets = j.queryForList("SELECT t.id, t.name, t.version FROM testsets t WHERE t.status = 'FINAL' AND t.version = (SELECT max(x.version) FROM testsets x WHERE x.name = t.name AND x.status = 'FINAL') "
                + "AND EXISTS (SELECT 1 FROM test_questions q WHERE q.testset_id = t.id AND q.gold_chunk_spans::text LIKE ?) ORDER BY t.finalized_at DESC NULLS LAST LIMIT 1", "%" + docId + "%");
        if (sets.isEmpty()) {
            finish(j, docId, version, "NO_QUESTIONS", "No final test set has a question about this document, so there is nothing to re-locate or re-run.", null, null, null, 0, 0);
            return;
        }
        UUID setId = (UUID) sets.get(0).get("id");
        String setName = (String) sets.get(0).get("name");
        if (!j.queryForList("SELECT 1 FROM testsets WHERE name = ? AND status IN ('DRAFT', 'GENERATING')", setName).isEmpty()) {
            finish(j, docId, version, "SKIPPED", "\"" + setName + "\" has a draft version in progress. Finish or finalize it, then replace the document again to see the impact.", null, null, null, 0, 0);
            return;
        }

        // 1. a new version of the test set, with re-located spans
        UUID newSet = j.queryForObject("INSERT INTO testsets (workspace_id, name, version, parent_version_id, status, params, created_by) "
                + "SELECT workspace_id, name, (SELECT max(version) + 1 FROM testsets WHERE name = ?), id, 'DRAFT', params, NULL FROM testsets WHERE id = ? RETURNING id", UUID.class, setName, setId);
        j.update("INSERT INTO test_questions (workspace_id, testset_id, stable_id, question, expected_answer, gold_chunk_spans, qtype, origin, review_status, quality_flags, source_trace_id, meta) "
                + "SELECT workspace_id, ?, stable_id, question, expected_answer, gold_chunk_spans, qtype, origin, review_status, quality_flags, source_trace_id, meta "
                + "FROM test_questions WHERE testset_id = ? AND review_status <> 'REJECTED' ORDER BY seq", newSet, setId);
        // A span points at the document version the test set was written against, which can be older than the one just replaced: an earlier
        // replacement may have been skipped (a draft was open) or the test set was not touched by it. Each span is re-located from its own version's text.
        Map<UUID, String> earlier = new HashMap<>();
        for (var v : j.queryForList("SELECT id, normalized_text FROM document_versions WHERE document_id = ? AND version < ?", docId, version))
            if (v.get("normalized_text") != null) earlier.put((UUID) v.get("id"), (String) v.get("normalized_text"));
        int relocated = 0, stale = 0;
        var rows = j.queryForList("SELECT id, gold_chunk_spans::text AS spans, review_status FROM test_questions WHERE testset_id = ? AND gold_chunk_spans::text LIKE ?", newSet, "%" + docId + "%");
        for (var row : rows) {
            List<GoldSpan> spans = json.readValue((String) row.get("spans"), new TypeReference<List<GoldSpan>>() {});
            List<GoldSpan> out = new ArrayList<>();
            boolean touched = false, lost = false;
            for (GoldSpan s : spans) {
                if (!s.documentId().equals(docId) || s.versionId().equals(newVersionId)) {
                    out.add(s);
                    continue;
                }
                touched = true;
                String from = earlier.get(s.versionId());
                var found = from == null ? java.util.Optional.<SpanRelocator.Located>empty() : SpanRelocator.relocate(from, newText, s.charStart(), s.charEnd());
                if (found.isEmpty()) {
                    lost = true;
                    out.add(s);
                } else {
                    var f = found.get();
                    out.add(new GoldSpan(docId, newVersionId, f.start(), f.end(), GoldSpan.snippet(newText, f.start(), f.end())));
                }
            }
            if (!touched) continue;
            if (lost) {
                stale++;
                j.update("UPDATE test_questions SET review_status = 'PENDING', quality_flags = array_append(quality_flags, ?) WHERE id = ? AND NOT (? = ANY (quality_flags))", STALE, row.get("id"), STALE);
            } else {
                relocated++;
                j.update("UPDATE test_questions SET gold_chunk_spans = ?::jsonb WHERE id = ?", json.writeValueAsString(out), row.get("id"));
            }
        }
        int usable = j.queryForObject("SELECT count(*) FROM test_questions WHERE testset_id = ? AND review_status IN ('ACCEPTED', 'EDITED')", Integer.class, newSet);
        j.update("UPDATE testsets SET target_count = ?, generated_count = ? WHERE id = ?", rows.size() + usable, rows.size() + usable, newSet);
        if (usable == 0) {
            finish(j, docId, version, "SKIPPED", "Every question about this document is now stale, so the new version of the test set stays a draft for review.", newSet, null, null, relocated, stale);
            return;
        }
        j.update("UPDATE testsets SET status = 'FINAL', finalized_at = now() WHERE id = ?", newSet);

        // 2. re-run what was measured last on this test set, as a regression experiment on the new version
        var previous = j.queryForList("SELECT r.id AS run_id, c.config::text AS config FROM runs r JOIN experiments e ON e.id = r.experiment_id JOIN testsets t ON t.id = e.testset_id "
                + "JOIN pipeline_configs c ON c.id = r.config_id WHERE e.kind <> 'AUTOTUNE' AND r.status = 'DONE' AND t.name = ? AND t.version < (SELECT version FROM testsets WHERE id = ?) "
                + "ORDER BY r.finished_at DESC NULLS LAST LIMIT 1", setName, newSet);
        UUID previousRun = previous.isEmpty() ? null : (UUID) previous.get(0).get("run_id");
        PipelineConfig config = previous.isEmpty() ? defaults.promoted(ws).orElseGet(() -> PipelineConfig.defaults(embeddingModel))
                : json.readValue((String) previous.get(0).get("config"), PipelineConfig.class);
        int questions = j.queryForObject("SELECT count(*) FROM test_questions WHERE testset_id = ? AND review_status IN ('ACCEPTED', 'EDITED') AND cardinality(ARRAY(SELECT 1 FROM jsonb_array_elements(gold_chunk_spans))) > 0",
                Integer.class, newSet);
        UUID experiment = j.queryForObject("INSERT INTO experiments (workspace_id, testset_id, name, mode, kind, question_count) VALUES (?, ?, ?, 'RETRIEVAL', 'REGRESSION', ?) RETURNING id",
                UUID.class, ws, newSet, "Regression: " + title + " v" + version, questions);
        UUID configId = experiments.upsertConfig(j, ws, config);
        j.update("INSERT INTO runs (workspace_id, experiment_id, config_id, label, ordinal, meta) VALUES (?, ?, ?, ?, 0, ?::jsonb)", ws, experiment, configId,
                title + " v" + version, json.writeValueAsString(Map.of("mode", "RETRIEVAL", "regression", true)));
        queue.enqueue(j, ws, RunExperimentHandler.TYPE, json.createObjectNode().put("experimentId", experiment.toString()), "experiment-" + experiment);
        finish(j, docId, version, "DONE", previous.isEmpty() ? "Nothing had been measured on this test set before, so the new run is the first measurement and has nothing to be compared with." : null,
                newSet, experiment, previousRun, relocated, stale);
        log.info("document {} v{}: {} spans re-located, {} stale, regression experiment {}", docId, version, relocated, stale, experiment);
    }

    private void finish(JdbcTemplate j, UUID docId, int version, String status, String note, UUID testset, UUID experiment, UUID previousRun, int relocated, int stale) {
        j.update("UPDATE change_impacts SET status = ?, note = ?, testset_id = ?, experiment_id = ?, previous_run_id = ?, relocated = ?, stale = ? WHERE document_id = ? AND version = ?",
                status, note, testset, experiment, previousRun, relocated, stale, docId, version);
    }
}
