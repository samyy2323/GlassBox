package dev.glassbox.diagnose;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.diagnose.FailureClassifier.Stage;
import dev.glassbox.diagnose.FailureClassifier.Sub;
import dev.glassbox.diagnose.Recommendations.Recommendation;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.Chunking;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalParams;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecommendationsTest {
    static final PipelineConfig BASE = new PipelineConfig(new RetrievalParams(RetrievalParams.Mode.VECTOR, "bge-small-en-v1.5", 50, 50, 60, 1, 1, 50, 100,
            RetrievalParams.KeywordMatch.ANY), Reranker.Type.NONE, 50, 5, 2000);

    static Map<Stage, Integer> counts(Object... pairs) {
        Map<Stage, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((Stage) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    @Test
    void sayHowManyFailuresAreRankingMissesAndSuggestAReranker() {
        List<Recommendation> r = Recommendations.build(counts(Stage.RANKING_MISS, 38, Stage.RETRIEVAL_MISS, 62), Map.of(), BASE.withMode(RetrievalParams.Mode.HYBRID));
        Recommendation ranking = r.stream().filter(x -> x.stage() == Stage.RANKING_MISS).findFirst().orElseThrow();
        assertThat(ranking.percent()).isEqualTo(38);
        assertThat(ranking.text()).isEqualTo("38% of failures are ranking misses, so add a reranker.");
        assertThat(ranking.change()).isEqualTo(Map.of("reranker", "CROSS_ENCODER"));
    }

    @Test
    void theLargestCauseComesFirst() {
        List<Recommendation> r = Recommendations.build(counts(Stage.CONTEXT_MISS, 3, Stage.RANKING_MISS, 9, Stage.RETRIEVAL_MISS, 6), Map.of(), BASE);
        assertThat(r).extracting(Recommendation::stage).containsExactly(Stage.RANKING_MISS, Stage.RETRIEVAL_MISS, Stage.CONTEXT_MISS);
    }

    @Test
    void aCauseBelowTenPercentGetsNoAdvice() {
        List<Recommendation> r = Recommendations.build(counts(Stage.RANKING_MISS, 95, Stage.CONTEXT_MISS, 5), Map.of(), BASE);
        assertThat(r).extracting(Recommendation::stage).containsExactly(Stage.RANKING_MISS);
        assertThat(Recommendations.build(Map.of(), Map.of(), BASE)).isEmpty();
    }

    @Test
    void retrievalAdviceStepsUpFromVectorToHybridToMoreCandidatesToABetterModel() {
        Map<Stage, Integer> c = counts(Stage.RETRIEVAL_MISS, 4);
        assertThat(Recommendations.build(c, Map.of(), BASE).get(0).change()).isEqualTo(Map.of("mode", "HYBRID"));
        PipelineConfig hybrid = BASE.withMode(RetrievalParams.Mode.HYBRID);
        assertThat(Recommendations.build(c, Map.of(), hybrid).get(0).change()).isEqualTo(Map.of("kFused", 100));
        PipelineConfig wider = new PipelineConfig(new RetrievalParams(RetrievalParams.Mode.HYBRID, "bge-small-en-v1.5", 100, 100, 60, 1, 1, 100, 100, RetrievalParams.KeywordMatch.ANY),
                Reranker.Type.NONE, 100, 5, 2000);
        assertThat(Recommendations.build(c, Map.of(), wider).get(0).change()).isEqualTo(Map.of("embeddingModel", "bge-base-en-v1.5"));
    }

    @Test
    void whenARerankerIsAlreadyOnRankingAdviceIsToPutMoreChunksInThePrompt() {
        Recommendation r = Recommendations.build(counts(Stage.RANKING_MISS, 2), Map.of(), BASE.withReranker(Reranker.Type.CROSS_ENCODER)).get(0);
        assertThat(r.change()).isEqualTo(Map.of("topN", 10));
    }

    @Test
    void contextAdviceDoublesTheBudgetUpToTheCap() {
        assertThat(Recommendations.build(counts(Stage.CONTEXT_MISS, 2), Map.of(), BASE).get(0).change()).isEqualTo(Map.of("tokenBudget", 4000));
        assertThat(Recommendations.build(counts(Stage.CONTEXT_MISS, 2), Map.of(), BASE.withTokenBudget(5000)).get(0).change()).isEqualTo(Map.of("tokenBudget", 6000));
    }

    @Test
    void chunkingAdviceGrowsTheChunksThenSwitchesToParentChild() {
        Map<Stage, Integer> c = counts(Stage.CHUNKING_MISS, 5);
        assertThat(Recommendations.build(c, Map.of(), BASE).get(0).change())
                .isEqualTo(Map.of("chunking", Map.of("strategy", "recursive", "size", 1024, "overlapPct", 0)));
        assertThat(Recommendations.build(c, Map.of(), BASE.withChunking(new Chunking("recursive", 1024, 0))).get(0).change())
                .isEqualTo(Map.of("chunking", Map.of("strategy", "parent_child", "size", 1024, "overlapPct", 0)));
    }

    @Test
    void generationAdviceFollowsTheSubLabelAndOffersNoOneClickChange() {
        Recommendation unfaithful = Recommendations.build(counts(Stage.GENERATION_MISS, 6), Map.of(Sub.UNFAITHFUL, 4, Sub.FALSE_ABSTENTION, 1), BASE).get(0);
        assertThat(unfaithful.sub()).isEqualTo(Sub.UNFAITHFUL);
        assertThat(unfaithful.text()).contains("stronger model or a stricter prompt");
        assertThat(unfaithful.change()).isNull();
        Recommendation refusing = Recommendations.build(counts(Stage.GENERATION_MISS, 6), Map.of(Sub.FALSE_ABSTENTION, 5), BASE).get(0);
        assertThat(refusing.text()).contains("loosen the abstention wording");
    }

    @Test
    void partialRetrievalOffersDecompositionFirstAndMoreChunksOnceItIsOn() {
        Recommendation first = Recommendations.build(counts(Stage.PARTIAL_RETRIEVAL, 3), Map.of(), BASE).get(0);
        assertThat(first.change()).isEqualTo(Map.of("transformer", "DECOMPOSE"));
        assertThat(first.text()).contains("split the question into sub-questions");
        Recommendation again = Recommendations.build(counts(Stage.PARTIAL_RETRIEVAL, 3), Map.of(), BASE.withTransformer(dev.glassbox.retrieval.transform.Transformer.DECOMPOSE)).get(0);
        assertThat(again.change()).isEqualTo(Map.of("topN", 8));
    }

    @Test
    void badTestQuestionsPointAtTheTestSetAndHallucinationsAtThePrompt() {
        Recommendation bad = Recommendations.build(counts(Stage.BAD_TEST_QUESTION, 3), Map.of(), BASE).get(0);
        assertThat(bad.text()).endsWith("so fix the test set, not the pipeline.");
        assertThat(bad.change()).isNull();
        assertThat(Recommendations.build(counts(Stage.HALLUCINATED_ANSWER, 3), Map.of(), BASE).get(0).text()).contains("unanswerable questions");
    }
}
