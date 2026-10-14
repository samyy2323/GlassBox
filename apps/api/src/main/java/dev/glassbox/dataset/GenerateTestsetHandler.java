package dev.glassbox.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.dataset.QuestionSampler.Chunk;
import dev.glassbox.dataset.QuestionSampler.Sample;
import dev.glassbox.embed.EmbeddingProvider;
import dev.glassbox.embed.EmbeddingProviders;
import dev.glassbox.generate.LlmProvider;
import dev.glassbox.generate.LlmProvider.Request;
import dev.glassbox.jobs.Job;
import dev.glassbox.jobs.JobHandler;
import dev.glassbox.retrieval.ChunkingResolver;
import dev.glassbox.tenancy.Tx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes a test set: samples passages, asks the model for a question and answer per passage, locates the gold evidence,
 * flags weak questions and stores each question as soon as it exists. Progress is stored with every question, so a crashed
 * or retried job continues where it stopped instead of starting over or writing duplicates.
 */
@Component
public class GenerateTestsetHandler implements JobHandler {
    public static final String TYPE = "GENERATE_TESTSET";
    private static final Logger log = LoggerFactory.getLogger(GenerateTestsetHandler.class);
    /** Samples attempted per wanted question: some model replies are unusable and are skipped. */
    private static final double OVERSAMPLE = 1.5;
    private static final int ATTEMPTS_PER_SAMPLE = 3;
    private static final Pattern DIGIT = Pattern.compile("\\d");

    private final Tx tx;
    private final ObjectMapper json;
    private final LlmProvider llm;
    private final LlmChecks checks;
    private final EmbeddingProviders embedders;
    private final ChunkingResolver chunkings;

    public GenerateTestsetHandler(Tx tx, ObjectMapper json, LlmProvider llm, LlmChecks checks, EmbeddingProviders embedders, ChunkingResolver chunkings) {
        this.tx = tx;
        this.json = json;
        this.llm = llm;
        this.checks = checks;
        this.embedders = embedders;
        this.chunkings = chunkings;
    }

    @Override
    public String type() {
        return TYPE;
    }

    /** What the sample asked for, corrected by what the model actually wrote: a numeric answer makes the question NUMERIC. */
    public static String label(String sampleType, String expectedAnswer) {
        if (sampleType.equals(QuestionSampler.FACTUAL) || sampleType.equals(QuestionSampler.NUMERIC)) {
            return DIGIT.matcher(expectedAnswer).find() ? QuestionSampler.NUMERIC : QuestionSampler.FACTUAL;
        }
        return sampleType;
    }

    private record Row(UUID id, String status, int target, int generated, int samplesDone, JsonNode params) {}

    @Override
    public void handle(Job job) throws Exception {
        UUID ws = job.workspaceId();
        UUID setId = UUID.fromString(job.payload().get("testsetId").asText());
        Row row = tx.workspace(ws, null, j -> j.query("SELECT id, status, target_count, generated_count, samples_done, params::text AS params "
                + "FROM testsets WHERE id = ?", (rs, n) -> {
            try {
                return new Row(rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("target_count"), rs.getInt("generated_count"),
                        rs.getInt("samples_done"), json.readTree(rs.getString("params")));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, setId).stream().findFirst().orElse(null));
        if (row == null || !row.status().equals("GENERATING")) return;

        Optional<UUID> chunkingId = chunkings.defaultChunkingId(ws);
        List<UUID> only = new ArrayList<>();
        if (row.params().hasNonNull("documentIds")) row.params().get("documentIds").forEach(n -> only.add(UUID.fromString(n.asText())));
        List<Chunk> chunks = chunkingId.map(id -> loadChunks(ws, id, only)).orElse(List.of());
        if (chunks.isEmpty()) {
            finish(ws, setId, "FAILED", "There are no ready documents to write questions about.");
            return;
        }

        long seed = row.params().path("seed").asLong(1);
        Map<String, Double> mix = new LinkedHashMap<>();
        row.params().path("mix").fields().forEachRemaining(e -> mix.put(e.getKey(), e.getValue().asDouble()));
        if (mix.isEmpty()) mix.putAll(QuestionSampler.defaultMix());
        UUID chunkingUuid = chunkingId.orElseThrow();
        int wanted = (int) Math.ceil(row.target() * OVERSAMPLE);
        List<Sample> samples = QuestionSampler.sample(chunks, wanted, mix, seed, c -> nearestInOtherDocument(ws, chunkingUuid, c, chunks));

        EmbeddingProvider embedder = embedders.get(embedders.defaultModel());
        List<float[]> earlier = new ArrayList<>();
        List<String> existing = tx.workspace(ws, null, j -> j.queryForList("SELECT question FROM test_questions WHERE testset_id = ? ORDER BY seq", String.class, setId));
        if (!existing.isEmpty()) earlier.addAll(embedder.embed(existing));

        Map<UUID, String> texts = new HashMap<>();
        int generated = row.generated();
        for (int i = row.samplesDone(); i < samples.size() && generated < row.target(); i++) {
            Sample s = samples.get(i);
            Optional<QuestionJson.Parsed> parsed = ask(s, seed);
            boolean stored = false;
            if (parsed.isPresent()) {
                stored = store(ws, setId, s, parsed.get(), i + 1, embedder, earlier, texts);
            }
            if (stored) generated++;
            else {
                log.info("testset {}: sample {} ({}) skipped: {}", setId, s.index(), s.type(), parsed.isPresent() ? "no evidence found in the passage" : "the model's reply was not usable");
                tx.workspace(ws, null, j -> j.update("UPDATE testsets SET samples_done = ? WHERE id = ?", s.index() + 1, setId));
            }
        }
        if (generated == 0) finish(ws, setId, "FAILED", "The model did not produce a single usable question.");
        else finish(ws, setId, "DRAFT", null);
        log.info("testset {}: {} questions from {} samples", setId, generated, samples.size());
    }

    @Override
    public void onDead(Job job, String error) {
        UUID setId = UUID.fromString(job.payload().get("testsetId").asText());
        finish(job.workspaceId(), setId, "FAILED", "Generation stopped: " + (error == null ? "unknown error" : error));
    }

    private Optional<QuestionJson.Parsed> ask(Sample s, long seed) {
        for (int attempt = 0; attempt < ATTEMPTS_PER_SAMPLE; attempt++) {
            int sampleSeed = (int) ((seed + s.index() * 31L + attempt) & 0x7fffffff);
            String raw = llm.complete(new Request(GenerationPrompts.generator(s.type(), s.chunks()), attempt == 0 ? 0.2 : 0.7, 300, true, sampleSeed));
            Optional<QuestionJson.Parsed> parsed = QuestionJson.parse(json, raw);
            if (parsed.isPresent()) return parsed;
            log.debug("unusable reply for sample {} attempt {}: {}", s.index(), attempt, raw == null ? null : raw.substring(0, Math.min(200, raw.length())));
        }
        return Optional.empty();
    }

    /** Locates evidence, runs the quality checks and saves the question with the progress counters. */
    private boolean store(UUID ws, UUID setId, Sample s, QuestionJson.Parsed p, int samplesDone, EmbeddingProvider embedder,
                          List<float[]> earlier, Map<UUID, String> texts) throws Exception {
        List<GoldSpan> spans = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        for (Chunk c : s.chunks()) {
            String doc = texts.computeIfAbsent(c.versionId(), v -> versionText(ws, v));
            if (doc == null) return false;
            var range = EvidenceLocator.locate(doc, c.charStart(), c.charEnd(), p.expectedAnswer(), p.question());
            if (range.isEmpty()) return false;
            spans.add(new GoldSpan(c.documentId(), c.versionId(), range.get().start(), range.get().end(),
                    GoldSpan.snippet(doc, range.get().start(), range.get().end())));
            evidence.add(doc.substring(range.get().start(), range.get().end()));
        }

        List<String> flags = new ArrayList<>();
        if (QualityFilters.copyRisk(p.question(), evidence)) flags.add(QualityFilters.COPY_RISK);
        boolean ambiguous = QualityFilters.deictic(p.question());
        if (!ambiguous && checks.enabled()) ambiguous = !checks.selfContained(p.question());
        if (ambiguous) flags.add(QualityFilters.AMBIGUOUS);
        if (checks.enabled()) {
            String own = checks.answerFrom(p.question(), evidence);
            if (QualityFilters.answerAgreement(p.expectedAnswer(), own) < QualityFilters.ANSWER_AGREEMENT) flags.add(QualityFilters.LOW_ANSWERABILITY);
        }
        float[] vector = embedder.embed(List.of(p.question())).get(0);
        if (QualityFilters.duplicate(vector, earlier)) flags.add(QualityFilters.DUPLICATE);
        earlier.add(vector);

        String spansJson = json.writeValueAsString(spans);
        String meta = json.writeValueAsString(Map.of("sampleIndex", s.index(), "sampleType", s.type()));
        String qtype = label(s.type(), p.expectedAnswer());
        tx.workspace(ws, null, j -> {
            j.update(con -> {
                var ps = con.prepareStatement("INSERT INTO test_questions (workspace_id, testset_id, question, expected_answer, gold_chunk_spans, "
                        + "qtype, origin, quality_flags, meta) VALUES (?, ?, ?, ?, ?::jsonb, ?, 'SYNTHETIC', ?, ?::jsonb)");
                ps.setObject(1, ws);
                ps.setObject(2, setId);
                ps.setString(3, p.question());
                ps.setString(4, p.expectedAnswer());
                ps.setString(5, spansJson);
                ps.setString(6, qtype);
                ps.setArray(7, con.createArrayOf("text", flags.toArray()));
                ps.setString(8, meta);
                return ps;
            });
            j.update("UPDATE testsets SET generated_count = generated_count + 1, samples_done = ? WHERE id = ?", s.index() + 1, setId);
            return null;
        });
        return true;
    }

    private String versionText(UUID ws, UUID versionId) {
        return tx.workspace(ws, null, j -> j.query("SELECT normalized_text FROM document_versions WHERE id = ?", (rs, n) -> rs.getString(1), versionId)
                .stream().findFirst().orElse(null));
    }

    private void finish(UUID ws, UUID setId, String status, String error) {
        tx.workspace(ws, null, j -> j.update("UPDATE testsets SET status = ?, error = ? WHERE id = ? AND status = 'GENERATING'", status, error, setId));
    }

    private List<Chunk> loadChunks(UUID ws, UUID chunkingId, List<UUID> documentIds) {
        String filter = documentIds.isEmpty() ? "" : " AND c.document_id = ANY (?)";
        return tx.workspace(ws, null, j -> j.query("SELECT c.id, c.document_id, c.document_version_id, d.title, c.ordinal, c.char_start, c.char_end, c.page, c.text "
                + "FROM chunks c JOIN documents d ON d.id = c.document_id "
                + "JOIN document_versions v ON v.id = c.document_version_id AND v.version = d.version "
                + "WHERE c.chunking_id = ? AND NOT c.superseded AND d.status = 'READY' AND d.superseded_by IS NULL" + filter
                + " ORDER BY c.document_id, c.ordinal", ps -> {
            ps.setObject(1, chunkingId);
            if (!documentIds.isEmpty()) ps.setArray(2, ps.getConnection().createArrayOf("uuid", documentIds.toArray()));
        }, (rs, n) -> new Chunk(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getObject("document_version_id", UUID.class),
                rs.getString("title"), rs.getInt("ordinal"), rs.getInt("char_start"), rs.getInt("char_end"), (Integer) rs.getObject("page"), rs.getString("text"))));
    }

    /** The closest chunk (by embedding) that belongs to a different document and is itself a usable passage. */
    private Optional<Chunk> nearestInOtherDocument(UUID ws, UUID chunkingId, Chunk from, List<Chunk> all) {
        String model = embedders.defaultModel();
        int dim = embedders.get(model).dimension();
        List<UUID> ids = tx.workspace(ws, null, j -> j.queryForList(
                "SELECT c.id FROM embeddings e JOIN chunks c ON c.id = e.chunk_id "
                        + "WHERE e.chunking_id = ? AND e.model = ? AND c.document_id <> ? AND NOT c.superseded "
                        + "ORDER BY e.vector::vector(" + dim + ") <=> (SELECT x.vector FROM embeddings x WHERE x.chunk_id = ? AND x.model = ?)::vector(" + dim + ") "
                        + "LIMIT 8", UUID.class, chunkingId, model, from.documentId(), from.id(), model));
        for (UUID id : ids) {
            for (Chunk c : all) if (c.id().equals(id) && QuestionSampler.useful(c)) return Optional.of(c);
        }
        return Optional.empty();
    }
}
