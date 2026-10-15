package dev.glassbox.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import dev.glassbox.FakeLlmProvider;
import dev.glassbox.generate.CachedLlm;
import dev.glassbox.generate.LlmProvider.Message;
import dev.glassbox.generate.LlmProvider.Request;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Query transformers inside real experiments, and the temperature-0 cache behind them. */
class TransformerApiTest extends EvalApiSupport {
    @Autowired CachedLlm cache;

    private static final String CONFIGS = "[{\"name\":\"plain\",\"mode\":\"HYBRID\"},{\"name\":\"multi\",\"mode\":\"HYBRID\",\"transformer\":\"MULTI_QUERY\"},"
            + "{\"name\":\"hyde\",\"mode\":\"HYBRID\",\"transformer\":\"HYDE\"},{\"name\":\"decompose\",\"mode\":\"HYBRID\",\"transformer\":\"DECOMPOSE\"}]";

    @Test
    void everyTransformerRunsInsideAnExperimentAndTheModelIsAskedOncePerQuestionAndTransformer() throws Exception {
        Account a = accountWithDocs(3, 6);
        String set = finalTestSet(a, "transform", 8);
        FakeLlmProvider.TRANSFORM_CALLS.set(0);
        JsonNode created = createExperiment(a, set, "transformers", CONFIGS);
        int questions = created.get("questionCount").asInt();
        drainQueue();

        JsonNode done = experiment(a, created.get("id").asText());
        assertThat(done.get("status").asText()).as("error: %s", done.get("error")).isEqualTo("DONE");
        for (JsonNode run : done.get("runs")) {
            assertThat(run.get("samplesDone").asInt()).isEqualTo(questions);
            // the corpus is built so a question about a section is answered by that section: a transformer must not lose it
            assertThat(metric(run, "recall@10", "all", "question").get("value").asDouble()).as(run.get("label").asText()).isGreaterThanOrEqualTo(0.75);
        }
        assertThat(done.get("runs").get(0).get("config").has("transformer")).as("no transformer is no key").isFalse();
        assertThat(done.get("runs").get(1).get("config").get("transformer").asText()).isEqualTo("MULTI_QUERY");
        assertThat(FakeLlmProvider.TRANSFORM_CALLS.get()).isEqualTo(3 * questions);

        // the same experiment again: every model call is served from the cache
        FakeLlmProvider.TRANSFORM_CALLS.set(0);
        createExperiment(a, set, "again", CONFIGS.replace("\"plain\"", "\"plain again\""));
        drainQueue();
        assertThat(FakeLlmProvider.TRANSFORM_CALLS.get()).as("repeated questions come from the cache").isZero();
        assertThat(count(a, "SELECT count(*) FROM llm_cache")).isEqualTo(3 * questions);
    }

    @Test
    void anUnusableReplyNeverMakesASearchWorseThanNoTransformer() throws Exception {
        Account a = accountWithDocs(2, 6);
        String set = finalTestSet(a, "garbled", 6);
        FakeLlmProvider.TRANSFORM_SCRIPT = r -> "\n\n";   // a blank reply
        String id = createExperiment(a, set, "blank", "[{\"name\":\"plain\",\"mode\":\"HYBRID\"},{\"name\":\"multi\",\"mode\":\"HYBRID\",\"transformer\":\"MULTI_QUERY\"}]").get("id").asText();
        drainQueue();
        JsonNode done = experiment(a, id);
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(metric(done.get("runs").get(1), "recall@10", "all", "question").get("value").asDouble())
                .isEqualTo(metric(done.get("runs").get(0), "recall@10", "all", "question").get("value").asDouble());
    }

    @Test
    void anUnknownTransformerIsRefusedAndTheEstimateCountsTheExtraModelCalls() throws Exception {
        Account a = accountWithDocs(2, 4);
        String set = finalTestSet(a, "estimate", 4);
        send(post("/api/experiments"), a, "{\"testsetId\":\"" + set + "\",\"name\":\"x\",\"configs\":[{\"name\":\"a\",\"transformer\":\"TELEPATHY\"}]}").andExpect(status().isBadRequest());

        JsonNode plain = ok(send(post("/api/experiments/estimate"), a, "{\"testsetId\":\"" + set + "\",\"configs\":[{\"name\":\"a\"}]}"));
        JsonNode multi = ok(send(post("/api/experiments/estimate"), a, "{\"testsetId\":\"" + set + "\",\"configs\":[{\"name\":\"a\",\"transformer\":\"HYDE\"},{\"name\":\"b\",\"transformer\":\"DECOMPOSE\"}]}"));
        assertThat(plain.get("llmCalls").asInt()).isZero();
        assertThat(multi.get("llmCalls").asInt()).isEqualTo(2 * multi.get("questions").asInt());
        assertThat(multi.get("estimatedTokens").asLong()).isPositive();
        List<String> notes = new java.util.ArrayList<>();
        multi.get("notes").forEach(n -> notes.add(n.asText()));
        assertThat(notes).anyMatch(n -> n.contains("Query transformers"));
    }

    @Test
    void theCacheServesTemperatureZeroCallsPerWorkspaceAndNeverCachesSampling() throws Exception {
        Account a = register(), b = register();
        UUID wa = a.workspace(), wb = b.workspace();
        Request cold = new Request(List.of(Message.system("You rewrite search queries. paraphrases"), Message.user("cache probe " + UUID.randomUUID())), 0.0, 50);
        FakeLlmProvider.TRANSFORM_CALLS.set(0);
        String first = cache.complete(wa, cold), second = cache.complete(wa, cold);
        assertThat(second).isEqualTo(first);
        assertThat(FakeLlmProvider.TRANSFORM_CALLS.get()).as("second call from the cache").isEqualTo(1);
        cache.complete(wb, cold);
        assertThat(FakeLlmProvider.TRANSFORM_CALLS.get()).as("another workspace has its own cache").isEqualTo(2);

        Request sampled = new Request(cold.messages(), 0.7, 50);
        cache.complete(wa, sampled);
        cache.complete(wa, sampled);
        assertThat(FakeLlmProvider.TRANSFORM_CALLS.get()).as("a sampled call is never cached").isEqualTo(4);
        assertThat(count(a, "SELECT count(*) FROM llm_cache")).isEqualTo(1);
        assertThat(count(b, "SELECT count(*) FROM llm_cache")).isEqualTo(1);
        assertThat(cache.hitRate()).isGreaterThan(0.0);
    }
}
