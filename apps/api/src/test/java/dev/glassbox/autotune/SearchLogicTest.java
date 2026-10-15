package dev.glassbox.autotune;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.glassbox.autotune.Objective.Score;
import dev.glassbox.autotune.Pareto.Point;
import dev.glassbox.autotune.SearchSpace.Candidate;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalParams;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The pure parts of AutoTune: halving, the quality blend, objectives, the Pareto frontier with its three picks, and the search space. */
class SearchLogicTest {
    // ------------------------------------------------------------ successive halving

    @Test
    void halvingStartsOnAQuarterKeepsAThirdAndDoublesUntilEveryQuestionIsUsed() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 18; i++) ids.add("c" + i);
        List<int[]> asked = new ArrayList<>();
        // candidate i is better the larger i is, whatever the sample
        var result = SuccessiveHalving.run(ids, 40, 4, 1, (id, size) -> {
            asked.add(new int[] {Integer.parseInt(id.substring(1)), size});
            return Integer.parseInt(id.substring(1)) / 100.0;
        }, Comparator.naturalOrder());
        assertThat(result.rounds()).extracting(SuccessiveHalving.Round::sample).containsExactly(10, 20, 40);
        assertThat(result.rounds().get(0).entered()).hasSize(18);
        assertThat(result.rounds().get(0).kept()).hasSize(6);   // a third of 18
        assertThat(result.rounds().get(1).entered()).hasSize(6);
        assertThat(result.rounds().get(1).kept()).hasSize(2);   // a third of 6
        assertThat(result.rounds().get(2).entered()).hasSize(2);
        assertThat(result.ranking()).containsExactly("c17", "c16");
        // the cheap candidates were only ever scored on the small sample
        assertThat(asked.stream().filter(a -> a[0] == 0).map(a -> a[1])).containsOnly(10);
        assertThat(asked.stream().filter(a -> a[0] == 17).map(a -> a[1])).containsExactly(10, 20, 40);
    }

    @Test
    void halvingKeepsEnoughFinalistsAndNeverFewerThanOne() {
        List<String> ids = List.of("a", "b", "c", "d", "e", "f", "g");
        var five = SuccessiveHalving.run(ids, 8, 2, 5, (id, s) -> (double) id.charAt(0), Comparator.naturalOrder());
        assertThat(five.ranking()).hasSize(5).first().isEqualTo("g");
        var one = SuccessiveHalving.run(List.of("only"), 8, 2, 1, (id, s) -> 0.5, Comparator.naturalOrder());
        assertThat(one.ranking()).containsExactly("only");
        assertThat(SuccessiveHalving.run(List.of(), 8, 2, 1, (id, s) -> 0.0, Comparator.naturalOrder()).ranking()).isEmpty();
    }

    @Test
    void halvingUsesTheTieBreakAndAllQuestionsWhenTheyAreFew() {
        var r = SuccessiveHalving.run(List.of("b", "a", "c"), 3, 10, 1, (id, s) -> 0.5, Comparator.naturalOrder());
        assertThat(r.rounds()).extracting(SuccessiveHalving.Round::sample).containsExactly(3);
        assertThat(r.ranking()).containsExactly("a", "b", "c");
    }

    // ------------------------------------------------------------ quality

    @Test
    void qualityBlendsFiftyThirtyTwentyAndRescalesAMissingComponent() {
        assertThat(Quality.blend(1.0, 1.0, 1.0)).isCloseTo(1.0, within(1e-12));
        assertThat(Quality.blend(0.8, 0.5, 1.0)).isCloseTo(0.5 * 0.8 + 0.3 * 0.5 + 0.2 * 1.0, within(1e-12));
        assertThat(Quality.blend(0.8, null, null)).as("retrieval-only: context recall alone").isCloseTo(0.8, within(1e-12));
        assertThat(Quality.blend(0.8, 0.5, null)).isCloseTo((0.5 * 0.8 + 0.3 * 0.5) / 0.8, within(1e-12));
        assertThat(Quality.blend(null, null, null)).isNaN();
    }

    // ------------------------------------------------------------ objectives

    @Test
    void objectivesOrderCandidatesTheirOwnWay() {
        List<Score> s = List.of(new Score("slowBest", 0.9, 800, 300), new Score("cheapOk", 0.75, 200, 120), new Score("cheapBad", 0.4, 100, 60),
                new Score("fastOk", 0.7, 500, 40));
        assertThat(order(s, Objective.BEST_QUALITY, 0, 0)).containsExactly("slowBest", "cheapOk", "fastOk", "cheapBad");
        assertThat(order(s, Objective.CHEAPEST_ABOVE_FLOOR, 0.7, 0)).containsExactly("cheapOk", "fastOk", "slowBest", "cheapBad");
        assertThat(order(s, Objective.CHEAPEST_ABOVE_FLOOR, 0.95, 0)).as("nobody reaches the floor: best quality first").containsExactly("slowBest", "cheapOk", "fastOk", "cheapBad");
        assertThat(order(s, Objective.FASTEST_WITHIN_CAP, 0, 150)).containsExactly("cheapOk", "fastOk", "cheapBad", "slowBest");
    }

    private static List<String> order(List<Score> s, Objective o, double floor, double cap) {
        return s.stream().sorted(o.comparator(floor, cap)).map(Score::id).toList();
    }

    // ------------------------------------------------------------ pareto

    @Test
    void theFrontierKeepsOnlyPointsNothingBeatsOnAllThree() {
        List<Point> pts = List.of(new Point("best", 0.9, 800, 300), new Point("cheap", 0.8, 200, 100), new Point("dominated", 0.7, 900, 400), new Point("fast", 0.75, 300, 20));
        assertThat(Pareto.frontier(pts)).extracting(Point::id).containsExactly("best", "cheap", "fast");
        assertThat(Pareto.frontier(List.of())).isEmpty();
        assertThat(Pareto.frontier(List.of(new Point("a", 0.5, 1, 1), new Point("a2", 0.5, 1, 1)))).as("identical points do not dominate each other").hasSize(2);
    }

    @Test
    void threeNamedRecommendations() {
        List<Point> pts = List.of(new Point("best", 0.90, 800, 300), new Point("value", 0.85, 200, 150), new Point("cheapJunk", 0.30, 20, 100),
                new Point("quick", 0.88, 700, 40), new Point("quickJunk", 0.60, 100, 5));
        var picks = Pareto.recommend(pts).orElseThrow();
        assertThat(picks.bestQuality()).isEqualTo("best");
        assertThat(picks.bestValue()).as("highest quality per prompt token among points with 90% of the best quality").isEqualTo("value");
        assertThat(picks.fastest()).as("fastest within 3 points of the best, so not the junk that is faster still").isEqualTo("quick");
        assertThat(Pareto.recommend(List.of())).isEmpty();
        var single = Pareto.recommend(List.of(new Point("only", 0.5, 1, 1))).orElseThrow();
        assertThat(single.bestQuality()).isEqualTo(single.bestValue()).isEqualTo(single.fastest()).isEqualTo("only");
    }

    @Test
    void effectsAverageQualityPerSettingAndSkipSettingsThatNeverVaried() {
        Map<String, Map<String, String>> settings = new HashMap<>();
        settings.put("a", Map.of("chunking", "fixed", "top n", "5"));
        settings.put("b", Map.of("chunking", "fixed", "top n", "10"));
        settings.put("c", Map.of("chunking", "structure", "top n", "5"));
        Map<String, Double> quality = Map.of("a", 0.6, "b", 0.8, "c", 0.9);
        var effects = Pareto.effects(settings, quality);
        assertThat(effects).filteredOn(e -> e.dimension().equals("chunking")).extracting(Pareto.Effect::value, Pareto.Effect::averageQuality)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("fixed", 0.7), org.assertj.core.groups.Tuple.tuple("structure", 0.9));
        assertThat(Pareto.effects(Map.of("a", Map.of("x", "1"), "b", Map.of("x", "1")), Map.of("a", 0.5, "b", 0.6))).isEmpty();
    }

    // ------------------------------------------------------------ search space

    @Test
    void stage1IsADeterministicSampleThatAlwaysHoldsTheBaseline() {
        List<String> models = List.of("bge-small-en-v1.5", "bge-base-en-v1.5", "all-MiniLM-L6-v2");
        List<Candidate> a = SearchSpace.stage1(7, 18, models, "bge-small-en-v1.5"), b = SearchSpace.stage1(7, 18, models, "bge-small-en-v1.5");
        assertThat(a).hasSize(18);
        assertThat(a.stream().map(Candidate::key)).containsExactlyElementsOf(b.stream().map(Candidate::key).toList());
        assertThat(a.stream().map(Candidate::key)).doesNotHaveDuplicates();
        assertThat(a.get(0).config()).isEqualTo(SearchSpace.baseline("bge-small-en-v1.5"));
        assertThat(SearchSpace.stage1(8, 18, models, "bge-small-en-v1.5").stream().map(Candidate::key)).isNotEqualTo(a.stream().map(Candidate::key).toList());
        // every candidate keeps the baseline's retrieval settings: stage 1 only varies chunking and model
        for (Candidate c : a) {
            assertThat(c.config().retrieval().mode()).isEqualTo(RetrievalParams.Mode.VECTOR);
            assertThat(c.config().topN()).isEqualTo(5);
        }
        assertThat(SearchSpace.stage1(7, 1, models, "bge-small-en-v1.5")).hasSize(1);
    }

    @Test
    void stage1CoversEveryChunkerAndModelInTheFullGrid() {
        List<String> models = List.of("bge-small-en-v1.5", "bge-base-en-v1.5", "all-MiniLM-L6-v2");
        List<Candidate> all = SearchSpace.stage1(1, 10_000, models, "bge-small-en-v1.5");
        Set<String> strategies = new HashSet<>();
        all.forEach(c -> strategies.add(c.settings().get("chunking")));
        assertThat(strategies).containsExactlyInAnyOrder("fixed", "recursive", "structure", "parent_child");
        assertThat(all).hasSize(18 * 3);
    }

    @Test
    void stage2OnlyVariesTheQueryTransformerWhenAskedTo() {
        PipelineConfig winner = SearchSpace.baseline("bge-small-en-v1.5");
        assertThat(SearchSpace.stage2(3, 10_000, winner)).allSatisfy(c -> assertThat(c.settings().get("query transform")).isEqualTo("none"));
        List<Candidate> with = SearchSpace.stage2(3, 10_000, winner, true);
        assertThat(with).hasSize(4 * 144);
        assertThat(with.stream().map(c -> c.settings().get("query transform")).distinct()).containsExactlyInAnyOrder("none", "multi-query", "hyde", "decompose");
        assertThat(with.get(0).config()).as("the unchanged winner stays first").isEqualTo(winner);
    }

    @Test
    void stage2KeepsTheWinnersChunkingAndModelAndVariesTheRest() {
        PipelineConfig winner = SearchSpace.config(new dev.glassbox.retrieval.Chunking("structure", 512, 0), "bge-base-en-v1.5", RetrievalParams.Mode.VECTOR, 1, 1, 50,
                Reranker.Type.NONE, 5, 3000);
        List<Candidate> c = SearchSpace.stage2(3, 24, winner);
        assertThat(c).hasSize(24);
        assertThat(c.get(0).config()).isEqualTo(winner);
        for (Candidate x : c) {
            assertThat(x.config().chunking()).isEqualTo(winner.chunking());
            assertThat(x.config().retrieval().model()).isEqualTo("bge-base-en-v1.5");
        }
        assertThat(c.stream().map(x -> x.settings().get("reranker")).distinct()).hasSizeGreaterThan(1);
        assertThat(c.stream().map(x -> x.settings().get("retriever")).distinct()).hasSizeGreaterThan(1);
        assertThat(SearchSpace.stage2(3, 10_000, winner)).hasSize(4 * 2 * 2 * 3 * 3 - 0 - 0).as("1 vector + 3 hybrid weightings x 2 k x 2 rerankers x 3 top n x 3 budgets, minus nothing");
    }
}
