package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Configurations that name their own chunking and embedding model: the missing indexes are built, coexist and are searched separately. */
class IndexedConfigsApiTest extends EvalApiSupport {
    private static final String CONFIGS = "[{\"name\":\"default\",\"mode\":\"VECTOR\"},"
            + "{\"name\":\"fixed 128\",\"mode\":\"VECTOR\",\"chunking\":{\"strategy\":\"fixed\",\"size\":128,\"overlapPct\":10}},"
            + "{\"name\":\"recursive 256, base\",\"mode\":\"HYBRID\",\"chunking\":{\"strategy\":\"recursive\",\"size\":256,\"overlapPct\":0},\"embeddingModel\":\"bge-base-en-v1.5\"},"
            + "{\"name\":\"parent child, minilm\",\"mode\":\"HYBRID\",\"chunking\":{\"strategy\":\"parent_child\",\"size\":256,\"overlapPct\":0},\"embeddingModel\":\"all-MiniLM-L6-v2\"}]";

    @Test
    void buildsTheMissingChunkingsAndModelsOnceAndSearchesEachOfThemSeparately() throws Exception {
        Account a = accountWithDocs(3, 6);
        String set = finalTestSet(a, "indexed", 8);
        assertThat(count(a, "SELECT count(*) FROM chunkings")).isEqualTo(1);

        JsonNode created = createExperiment(a, set, "chunkings and models", CONFIGS);
        String id = created.get("id").asText();
        int questions = created.get("questionCount").asInt();
        drainQueue();

        JsonNode done = experiment(a, id);
        assertThat(done.get("status").asText()).as("error: %s", done.get("error")).isEqualTo("DONE");
        for (JsonNode run : done.get("runs")) {
            assertThat(run.get("status").asText()).isEqualTo("DONE");
            assertThat(run.get("samplesDone").asInt()).isEqualTo(questions);
            assertThat(metric(run, "context_recall", "all", "question").get("n").asInt()).isEqualTo(questions);
        }

        // four chunkings now coexist, and each model embedded the chunks of its own chunking
        assertThat(count(a, "SELECT count(*) FROM chunkings")).isEqualTo(4);
        assertThat(count(a, "SELECT count(*) FROM embeddings WHERE model = 'bge-base-en-v1.5'")).isGreaterThan(0);
        assertThat(count(a, "SELECT count(*) FROM embeddings WHERE model = 'all-MiniLM-L6-v2'")).isGreaterThan(0);
        assertThat(count(a, "SELECT count(*) FROM embeddings e JOIN chunkings k ON k.id = e.chunking_id "
                + "WHERE e.model = 'bge-base-en-v1.5' AND k.strategy <> 'recursive'")).isZero();
        assertThat(count(a, "SELECT count(*) FROM embeddings e JOIN chunkings k ON k.id = e.chunking_id "
                + "WHERE e.model = 'all-MiniLM-L6-v2' AND k.strategy <> 'parent_child'")).isZero();

        // parent chunks are stored for the prompt but never embedded; every child points at a parent
        assertThat(count(a, "SELECT count(*) FROM chunks WHERE role = 'PARENT'")).isGreaterThan(0);
        assertThat(count(a, "SELECT count(*) FROM embeddings e JOIN chunks c ON c.id = e.chunk_id WHERE c.role = 'PARENT'")).isZero();
        assertThat(count(a, "SELECT count(*) FROM chunks WHERE role = 'CHILD' AND parent_chunk_id IS NULL")).isZero();

        // the parent_child run puts parents in the prompt, not the children it matched
        String parentChildRun = done.get("runs").get(3).get("id").asText();
        List<String> contextChunkIds = new ArrayList<>();
        for (JsonNode r : ok(send(get("/api/experiments/" + id + "/results?run=" + parentChildRun), a, null))) {
            for (JsonNode c : r.get("finalContext")) contextChunkIds.add(c.get("chunkId").asText());
        }
        assertThat(contextChunkIds).isNotEmpty();
        String ids = "'" + String.join("','", contextChunkIds) + "'";
        assertThat(count(a, "SELECT count(*) FROM chunks WHERE id IN (" + ids + ") AND role <> 'PARENT'")).isZero();

        // a config that names the default chunking is the config P3 stored: no "chunking" key, so the same hash
        assertThat(done.get("runs").get(0).get("config").has("chunking")).isFalse();
        assertThat(done.get("runs").get(1).get("config").get("chunking").get("strategy").asText()).isEqualTo("fixed");
        String same = "[{\"name\":\"explicit default\",\"mode\":\"VECTOR\",\"chunking\":{\"strategy\":\"recursive\",\"size\":512,\"overlapPct\":0}}]";
        int before = count(a, "SELECT count(*) FROM pipeline_configs");
        createExperiment(a, set, "same as default", same);
        assertThat(count(a, "SELECT count(*) FROM pipeline_configs")).as("reuses the stored default config").isEqualTo(before);
    }

    @Test
    void anIndexThatIsAlreadyBuiltIsNotBuiltAgain() throws Exception {
        Account a = accountWithDocs(2, 4);
        String set = finalTestSet(a, "again", 4);
        String cfg = "[{\"name\":\"base\",\"mode\":\"VECTOR\",\"embeddingModel\":\"bge-base-en-v1.5\"}]";
        createExperiment(a, set, "first", cfg);
        drainQueue();
        String vectors = "SELECT count(*) FROM embeddings WHERE model = 'bge-base-en-v1.5'";
        int embedded = count(a, vectors), chunks = count(a, "SELECT count(*) FROM chunks");
        assertThat(embedded).isGreaterThan(0);
        assertThat(count(a, "SELECT count(*) FROM embeddings WHERE model = 'bge-small-en-v1.5'")).isEqualTo(chunks);

        // (query vectors are embedded on every search, so only stored documents are compared)
        createExperiment(a, set, "second", cfg.replace("\"name\":\"base\"", "\"name\":\"base again\""));
        drainQueue();
        assertThat(count(a, vectors)).as("no document is embedded twice").isEqualTo(embedded);
        assertThat(count(a, "SELECT count(*) FROM chunks")).isEqualTo(chunks);
        assertThat(count(a, "SELECT count(*) FROM experiments WHERE status = 'DONE'")).isEqualTo(2);
    }

    @Test
    void theEstimateSaysWhatStillHasToBeIndexed() throws Exception {
        Account a = accountWithDocs(2, 4);
        String set = finalTestSet(a, "estimate", 4);
        JsonNode est = ok(send(post("/api/experiments/estimate"), a, "{\"testsetId\":\"" + set + "\",\"configs\":" + CONFIGS + "}"));
        List<String> notes = new ArrayList<>();
        est.get("notes").forEach(n -> notes.add(n.asText()));
        assertThat(notes).anyMatch(n -> n.startsWith("Indexing: 2 document(s)") && n.contains("fixed 128"));
        assertThat(notes).noneMatch(n -> n.contains("the default chunking"));
    }

    @Test
    void rejectsAChunkingOrModelItCannotBuild() throws Exception {
        Account a = accountWithDocs(1, 3);
        String set = finalTestSet(a, "bad", 2);
        for (String bad : List.of(
                "{\"name\":\"x\",\"chunking\":{\"strategy\":\"magic\",\"size\":256,\"overlapPct\":0}}",
                "{\"name\":\"x\",\"chunking\":{\"strategy\":\"fixed\",\"size\":4,\"overlapPct\":0}}",
                "{\"name\":\"x\",\"chunking\":{\"strategy\":\"fixed\",\"size\":256,\"overlapPct\":90}}",
                "{\"name\":\"x\",\"embeddingModel\":\"no-such-model\"}")) {
            send(post("/api/experiments"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"bad\",\"configs\":[" + bad + "]}")
                    .andExpect(status().isBadRequest());
        }
    }
}
