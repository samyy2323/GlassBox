package dev.glassbox.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.generate.CachedLlm;
import dev.glassbox.generate.LlmProvider.Request;
import dev.glassbox.retrieval.transform.ModelTransformers;
import dev.glassbox.retrieval.transform.QueryTransformer.Plan;
import dev.glassbox.retrieval.transform.Transformer;
import dev.glassbox.retrieval.transform.Transformers;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** The query transformers with scripted model replies, and the fusion of their result lists. */
class TransformerTest {
    private static final UUID WS = UUID.randomUUID();

    /** A cache-less stand-in that answers with whatever the test scripted, or fails. */
    private static CachedLlm model(Function<Request, String> reply, List<Request> seen) {
        return new CachedLlm(null, null) {
            @Override
            public String complete(UUID ws, Request request) {
                seen.add(request);
                return reply.apply(request);
            }
        };
    }

    private static Transformers all(CachedLlm llm) {
        return new Transformers(List.of(new ModelTransformers.MultiQuery(llm), new ModelTransformers.Hyde(llm), new ModelTransformers.Decompose(llm)));
    }

    private static List<String> keywords(Plan p) {
        return p.searches().stream().map(s -> s.keyword()).toList();
    }

    // ------------------------------------------------------------------ multi_query

    @Test
    void multiQueryKeepsTheQuestionFirstAndAddsAtMostThreeCleanParaphrases() {
        List<Request> seen = new ArrayList<>();
        Transformers t = all(model(r -> "Here are the paraphrases:\n1. How often is pump P-101 checked?\n2) \"How frequently is pump P-101 inspected\"\n- How often is pump P-101 inspected?\n* When must pump P-101 be inspected?\n\nWhat is the inspection interval of P-101?", seen));
        Plan p = t.plan(WS, Transformer.MULTI_QUERY, "How often is pump P-101 inspected?");
        assertThat(keywords(p)).containsExactly("How often is pump P-101 inspected?", "How often is pump P-101 checked?", "How frequently is pump P-101 inspected",
                "When must pump P-101 be inspected?");
        assertThat(p.llmCalls()).isEqualTo(1);
        assertThat(p.tokens()).isGreaterThan(20);
        assertThat(p.searches()).allSatisfy(s -> assertThat(s.embed()).isEqualTo(s.keyword()));
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).temperature()).as("temperature 0, so the call can be cached").isZero();
    }

    // ------------------------------------------------------------------ hyde

    @Test
    void hydeEmbedsTheHypotheticalAnswerButKeepsTheQuestionForKeywords() {
        Transformers t = all(model(r -> "Pump P-101 at Northfield is inspected every 14 days by the maintenance lead.", new ArrayList<>()));
        Plan p = t.plan(WS, Transformer.HYDE, "How often is pump P-101 inspected?");
        assertThat(p.searches()).hasSize(1);
        assertThat(p.searches().get(0).keyword()).isEqualTo("How often is pump P-101 inspected?");
        assertThat(p.searches().get(0).embed()).startsWith("Pump P-101 at Northfield");
    }

    @Test
    void hydeFallsBackToTheQuestionWhenTheAnswerIsTooShortToBeAPassage() {
        Plan p = all(model(r -> "I don't know", new ArrayList<>())).plan(WS, Transformer.HYDE, "How often is pump P-101 inspected?");
        assertThat(p.searches().get(0).embed()).isEqualTo("How often is pump P-101 inspected?");
    }

    // ------------------------------------------------------------------ decompose

    @Test
    void decomposeAddsSubQuestionsAfterTheOriginalAndCapsThemAtFour() {
        Transformers t = all(model(r -> "What is the drill interval at Northfield?\nWhat is the drill interval at Eastport?\nWhat is the target time at Northfield?\n"
                + "What is the target time at Eastport?\nWhat is the assembly point?", new ArrayList<>()));
        Plan p = t.plan(WS, Transformer.DECOMPOSE, "Compare the fire drills at Northfield and Eastport");
        assertThat(keywords(p)).hasSize(5);
        assertThat(keywords(p).get(0)).isEqualTo("Compare the fire drills at Northfield and Eastport");
        assertThat(keywords(p)).doesNotContain("What is the assembly point?");
    }

    @Test
    void aSimpleQuestionReturnedUnchangedAddsNothing() {
        Plan p = all(model(r -> "How often is pump P-101 inspected?", new ArrayList<>())).plan(WS, Transformer.DECOMPOSE, "How often is pump P-101 inspected?");
        assertThat(keywords(p)).containsExactly("How often is pump P-101 inspected?");
    }

    // ------------------------------------------------------------------ never worse than no transformer

    @Test
    void aFailingOrEmptyModelFallsBackToTheOriginalQuestion() {
        for (Transformer type : List.of(Transformer.MULTI_QUERY, Transformer.HYDE, Transformer.DECOMPOSE)) {
            Plan failed = all(model(r -> { throw new IllegalStateException("model down"); }, new ArrayList<>())).plan(WS, type, "What is the limit?");
            assertThat(keywords(failed)).as("%s after an error", type).containsExactly("What is the limit?");
            assertThat(failed.llmCalls()).isZero();
            Plan empty = all(model(r -> "", new ArrayList<>())).plan(WS, type, "What is the limit?");
            assertThat(keywords(empty)).as("%s after an empty reply", type).containsExactly("What is the limit?");
        }
    }

    @Test
    void noneAndNullSearchTheQuestionAsWrittenWithoutCallingTheModel() {
        List<Request> seen = new ArrayList<>();
        Transformers t = all(model(r -> "x", seen));
        assertThat(keywords(t.plan(WS, Transformer.NONE, "q?"))).containsExactly("q?");
        assertThat(keywords(t.plan(WS, null, "q?"))).containsExactly("q?");
        assertThat(seen).isEmpty();
    }

    @Test
    void everyTransformerHasACostEntry() {
        assertThat(Transformer.NONE.llmCalls()).isZero();
        for (Transformer t : List.of(Transformer.MULTI_QUERY, Transformer.HYDE, Transformer.DECOMPOSE)) {
            assertThat(t.llmCalls()).isEqualTo(1);
            assertThat(t.tokens()).isPositive();
        }
    }

    // ------------------------------------------------------------------ fusion

    private static Candidate cand(UUID id, int vecRank) {
        return new Candidate(id, UUID.randomUUID(), "text", 10, 0, 10, null, 0.5, vecRank, 0.1, null, null, null);
    }

    @Test
    void aChunkSeveralQueriesAgreeOnBeatsOneOnlyASingleQueryFound() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID(), d = UUID.randomUUID();
        // query 1: a, b, c   query 2: d, c, b   query 3: c, a
        var fused = QueryFusion.fuse(List.of(List.of(cand(a, 1), cand(b, 2), cand(c, 3)), List.of(cand(d, 1), cand(c, 2), cand(b, 3)), List.of(cand(c, 1), cand(a, 2))), 60, 10);
        // c: 1/63 + 1/62 + 1/61, a: 1/61 + 1/62, b: 1/62 + 1/63, d: 1/61
        assertThat(fused).extracting(Candidate::chunkId).containsExactly(c, a, b, d);
        assertThat(fused.get(0).fusedScore()).isEqualTo(1.0 / 63 + 1.0 / 62 + 1.0 / 61, org.assertj.core.api.Assertions.within(1e-12));
        assertThat(fused.get(0).vecRank()).as("details come from the first list that had the chunk").isEqualTo(3);
    }

    @Test
    void fusionRespectsTheLimitBreaksTiesByArrivalAndLeavesASingleListAlone() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        var fused = QueryFusion.fuse(List.of(List.of(cand(a, 1), cand(b, 2)), List.of(cand(c, 1))), 60, 2);
        assertThat(fused).extracting(Candidate::chunkId).containsExactly(a, c);   // a and c tie at 1/61; a arrived first
        List<Candidate> single = List.of(cand(a, 1), cand(b, 2));
        assertThat(QueryFusion.fuse(List.of(single), 60, 1)).isSameAs(single);
    }
}
