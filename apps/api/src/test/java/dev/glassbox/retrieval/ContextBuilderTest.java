package dev.glassbox.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.retrieval.ContextBuilder.Context;
import dev.glassbox.retrieval.ContextBuilder.Reason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContextBuilderTest {
    final ContextBuilder builder = new ContextBuilder();
    final UUID doc = UUID.randomUUID();

    Candidate chunk(UUID document, String text, int tokens, int start, int end) {
        return new Candidate(UUID.randomUUID(), document, text, tokens, start, end, 1, 0.5, 1, 0.1, null, null, null);
    }

    @Test
    void packsInRankOrderAndLabelsForCitation() {
        Context ctx = builder.build(List.of(chunk(doc, "alpha", 100, 0, 50), chunk(doc, "beta", 100, 100, 150)), 1000);
        assertThat(ctx.included()).extracting(ContextBuilder.ContextChunk::label).containsExactly("c1", "c2");
        assertThat(ctx.tokensUsed()).isEqualTo(200);
        assertThat(ctx.dropped()).isEmpty();
    }

    @Test
    void dropsWhatDoesNotFitButStillTriesSmallerOnesAndRecordsWhy() {
        Candidate big = chunk(doc, "big", 700, 0, 70);
        Candidate tooBig = chunk(doc, "too big", 500, 100, 150);
        Candidate small = chunk(doc, "small", 200, 200, 220);
        Context ctx = builder.build(List.of(big, tooBig, small), 1000);
        assertThat(ctx.included()).extracting(c -> c.chunk().text()).containsExactly("big", "small");
        assertThat(ctx.dropped()).singleElement().satisfies(d -> {
            assertThat(d.chunk()).isSameAs(tooBig);
            assertThat(d.reason()).isEqualTo(Reason.BUDGET);
            assertThat(d.detail()).contains("1200").contains("1000");
        });
        assertThat(ctx.tokensUsed()).isEqualTo(900);
    }

    @Test
    void aSingleChunkLargerThanTheBudgetIsDroppedNotTruncated() {
        Context ctx = builder.build(List.of(chunk(doc, "huge", 5000, 0, 100)), 3000);
        assertThat(ctx.included()).isEmpty();
        assertThat(ctx.dropped()).extracting(ContextBuilder.Dropped::reason).containsExactly(Reason.BUDGET);
    }

    @Test
    void removesDuplicateTextEvenAcrossDocumentsAndIgnoringCaseAndSpacing() {
        Context ctx = builder.build(List.of(
                chunk(doc, "Pumps  must be inspected.", 10, 0, 25),
                chunk(UUID.randomUUID(), "pumps must be\ninspected.", 10, 0, 25)), 1000);
        assertThat(ctx.included()).hasSize(1);
        assertThat(ctx.dropped()).extracting(ContextBuilder.Dropped::reason).containsExactly(Reason.DUPLICATE);
    }

    @Test
    void removesChunksWhoseSpanMostlyOverlapsAnIncludedOne() {
        Candidate first = chunk(doc, "first window of text", 100, 0, 1000);
        Candidate mostlyOverlapping = chunk(doc, "second window shifted a little", 100, 400, 1400); // 600 of 1000 shared
        Candidate slightlyOverlapping = chunk(doc, "third window", 100, 900, 1900);                   // 100 of 1000 shared
        Context ctx = builder.build(List.of(first, mostlyOverlapping, slightlyOverlapping), 1000);
        assertThat(ctx.included()).extracting(c -> c.chunk().text()).containsExactly("first window of text", "third window");
        assertThat(ctx.dropped()).singleElement().satisfies(d -> {
            assertThat(d.reason()).isEqualTo(Reason.OVERLAP);
            assertThat(d.detail()).contains("c1");
        });
    }

    @Test
    void sameOffsetsInDifferentDocumentsDoNotOverlap() {
        Context ctx = builder.build(List.of(chunk(doc, "one", 10, 0, 100), chunk(UUID.randomUUID(), "two", 10, 0, 100)), 1000);
        assertThat(ctx.included()).hasSize(2);
    }

    @Test
    void emptyInputGivesEmptyContext() {
        Context ctx = builder.build(List.of(), 3000);
        assertThat(ctx.included()).isEmpty();
        assertThat(ctx.tokensUsed()).isZero();
        assertThat(ctx.budget()).isEqualTo(3000);
    }
}
