package dev.glassbox.trace;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.generate.CitationParser;
import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.Candidate;
import dev.glassbox.retrieval.ContextBuilder;
import dev.glassbox.retrieval.PipelineConfig;
import dev.glassbox.retrieval.RetrievalPipeline;
import dev.glassbox.trace.TraceModel.CandidateView;
import dev.glassbox.trace.TraceModel.Disposition;
import dev.glassbox.trace.TraceModel.TraceData;
import dev.glassbox.trace.TraceModel.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What X-Ray tells the user about each retrieved chunk, as a decision table over a hand-built retrieval result. */
class TraceAssemblerTest {
    final TraceAssembler assembler = new TraceAssembler();
    final UUID doc = UUID.randomUUID();
    final Map<UUID, String> titles = Map.of(doc, "Pump manual");

    Candidate c(String text, int tokens, int start, int end, double fused, Integer vecRank, Integer kwRank) {
        return new Candidate(UUID.randomUUID(), doc, text, tokens, start, end, 2, fused, vecRank, vecRank == null ? null : 0.1 * vecRank,
                kwRank, kwRank == null ? null : 0.5, null);
    }

    /** Candidates, in fused order: A (in context), B (duplicate of A, dropped), C (budget, dropped), D (ranked below top N), E (outside the pool). */
    @Test
    void everyCandidateGetsOneDispositionWithAReasonWhenItDidNotReachThePrompt() {
        Candidate a = c("alpha text", 10, 0, 100, 0.9, 1, 1);
        Candidate b = c("alpha text", 10, 200, 300, 0.8, 2, null);          // identical text to A
        Candidate cc = c("charlie " + "x ".repeat(50), 600, 400, 900, 0.7, 3, 2);   // too big for the budget
        Candidate d = c("delta", 10, 1000, 1100, 0.6, 4, null);
        Candidate e = c("echo", 10, 1200, 1300, 0.5, null, 3);

        PipelineConfig config = new PipelineConfig(PipelineConfig.defaults("bge-small-en-v1.5").retrieval(), Reranker.Type.CROSS_ENCODER, 4, 3, 500);
        // The reranker saw the first 4 fused candidates, reordered them, and the top 3 went to the context builder.
        List<Candidate> reranked = List.of(a.withRerankScore(9.0), b.withRerankScore(8.0), cc.withRerankScore(5.0), d.withRerankScore(1.0));
        List<Candidate> ranked = reranked.subList(0, 3);
        ContextBuilder.Context context = new ContextBuilder().build(ranked, 500);
        var result = new RetrievalPipeline.Result("q", UUID.randomUUID(), config, List.of(a, b, cc, d, e), reranked, ranked, context, List.of());

        TraceData data = assembler.assemble(result, titles, List.of(), new CitationParser.Parsed(List.of(), List.of()), new Usage(10, 5), null);
        Map<UUID, CandidateView> byId = new java.util.HashMap<>();
        data.candidates().forEach(v -> byId.put(v.chunkId(), v));

        assertThat(byId.get(a.chunkId()).disposition()).isEqualTo(Disposition.IN_CONTEXT);
        assertThat(byId.get(a.chunkId()).label()).isEqualTo("c1");
        assertThat(byId.get(a.chunkId()).rerankScore()).isEqualTo(9.0);
        assertThat(byId.get(a.chunkId()).rankedPosition()).isEqualTo(1);

        assertThat(byId.get(b.chunkId()).disposition()).isEqualTo(Disposition.DROPPED);
        assertThat(byId.get(b.chunkId()).reason()).startsWith("DUPLICATE");

        assertThat(byId.get(cc.chunkId()).disposition()).isEqualTo(Disposition.DROPPED);
        assertThat(byId.get(cc.chunkId()).reason()).startsWith("BUDGET").contains("500");

        assertThat(byId.get(d.chunkId()).disposition()).isEqualTo(Disposition.CUT);
        assertThat(byId.get(d.chunkId()).reason()).isEqualTo("ranked #4, below the top 3");
        assertThat(byId.get(d.chunkId()).rerankScore()).as("cut chunks keep their rerank score").isEqualTo(1.0);

        assertThat(byId.get(e.chunkId()).disposition()).isEqualTo(Disposition.CUT);
        assertThat(byId.get(e.chunkId()).reason()).contains("outside the first 4");
        assertThat(byId.get(e.chunkId()).rankedPosition()).isNull();

        assertThat(data.candidates()).extracting(CandidateView::title).containsOnly("Pump manual");
        assertThat(data.context().included()).hasSize(1);
        assertThat(data.context().dropped()).hasSize(2);
        assertThat(data.context().tokensUsed()).isEqualTo(10);
        assertThat(data.usage()).isEqualTo(new Usage(10, 5));
    }

    @Test
    void snippetsAreFlattenedAndTruncated() {
        assertThat(TraceAssembler.snippet("one\n\n  two   three")).isEqualTo("one two three");
        String snippet = TraceAssembler.snippet("word ".repeat(200));
        assertThat(snippet).hasSize(TraceAssembler.SNIPPET_CHARS + 1).endsWith("…");
    }

    @Test
    void aFailureBeforeRetrievalStillProducesAnEmptyButValidTrace() {
        TraceData data = assembler.assemble(null, Map.of(), null, null, new Usage(0, 0), "embedding service down");
        assertThat(data.candidates()).isEmpty();
        assertThat(data.context()).isNull();
        assertThat(data.error()).isEqualTo("embedding service down");
        assertThat(new ArrayList<>(data.citations())).isEmpty();
    }
}
