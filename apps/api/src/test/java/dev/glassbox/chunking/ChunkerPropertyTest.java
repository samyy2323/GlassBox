package dev.glassbox.chunking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.chunking.Chunker.ChunkOut;
import dev.glassbox.parse.ParsedDocument;
import dev.glassbox.parse.ParsedDocument.Block;
import dev.glassbox.parse.ParsedDocument.Kind;
import dev.glassbox.parse.TextBuilder;
import java.util.List;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

/** Property tests for the chunkers (SPEC 14): no text lost, offsets correct, size bounds respected. */
class ChunkerPropertyTest {

    // ------------------------------------------------------------ generators

    @Provide
    Arbitrary<ParsedDocument> docs() {
        Arbitrary<String> word = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(9);
        Arbitrary<String> sentence = word.list().ofMinSize(2).ofMaxSize(14).map(w -> String.join(" ", w) + ".");
        Arbitrary<String> paragraph = sentence.list().ofMinSize(1).ofMaxSize(9).map(s -> String.join(" ", s));
        Arbitrary<Object[]> block = Combinators.combine(Arbitraries.integers().between(0, 9), paragraph, Arbitraries.integers().between(1, 3), Arbitraries.of(true, false))
                .as((k, text, level, newPage) -> new Object[] {k, text, level, newPage});
        return block.list().ofMinSize(1).ofMaxSize(14).map(ChunkerPropertyTest::build);
    }

    private static ParsedDocument build(List<Object[]> blocks) {
        TextBuilder tb = new TextBuilder();
        tb.startPage();
        for (Object[] b : blocks) {
            int kind = (Integer) b[0];
            String text = (String) b[1];
            if ((Boolean) b[3] && kind == 9) tb.startPage();
            if (kind <= 1) tb.heading((Integer) b[2], text.length() > 40 ? text.substring(0, 40) : text);
            else if (kind == 2) tb.block(Kind.LIST_ITEM, text);
            else if (kind == 3) tb.block(Kind.TABLE_ROW, text);
            else tb.block(Kind.PARAGRAPH, text);
        }
        return tb.build();
    }

    // ------------------------------------------------------------ shared checks

    private static void assertSound(ParsedDocument doc, List<ChunkOut> chunks, boolean verbatim) {
        TokenIndex tokens = new TokenIndex(doc.text());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkOut c = chunks.get(i);
            assertThat(c.ordinal()).isEqualTo(i);
            assertThat(c.start()).isBetween(0, doc.text().length());
            assertThat(c.end()).isBetween(c.start() + 1, doc.text().length());
            String original = doc.text().substring(c.start(), c.end());
            if (verbatim) assertThat(c.text()).isEqualTo(original); else assertThat(c.text()).endsWith(original);
            assertThat(c.tokenCount()).isEqualTo(tokens.count(c.start(), c.end()));
            assertThat(c.page()).isEqualTo(doc.pageAt(c.start()));
            if (i > 0) assertThat(c.start()).isGreaterThanOrEqualTo(chunks.get(i - 1).start());
        }
        // No text lost: every token of the document lies inside some chunk.
        for (int t = 0; t < tokens.size(); t++) {
            int ts = tokens.start(t), te = tokens.end(t);
            assertThat(chunks).as("token %d [%d,%d) is covered", t, ts, te)
                    .anyMatch(c -> c.start() <= ts && te <= c.end());
        }
    }

    private static void assertWithinSections(ParsedDocument doc, List<ChunkOut> chunks) {
        for (ChunkOut c : chunks) {
            var s = doc.sections().get(c.sectionOrdinal());
            assertThat(c.start()).isGreaterThanOrEqualTo(s.start());
            assertThat(c.end()).isLessThanOrEqualTo(s.end());
        }
    }

    // ------------------------------------------------------------ fixed

    @Property(tries = 150)
    void fixedChunksAreSoundAndBounded(@ForAll("docs") ParsedDocument doc,
                                       @ForAll @IntRange(min = 4, max = 60) int size,
                                       @ForAll @IntRange(min = 0, max = 3) int overlapQuarters) {
        int overlap = size * overlapQuarters / 4 - (overlapQuarters == 4 ? 1 : 0);
        List<ChunkOut> chunks = new FixedChunker(size, overlap).chunk(doc);
        assertSound(doc, chunks, true);
        assertThat(chunks).allSatisfy(c -> assertThat(c.tokenCount()).isLessThanOrEqualTo(size));
        // Consecutive windows overlap by exactly 'overlap' tokens (except the last, which may be shorter).
        TokenIndex tokens = new TokenIndex(doc.text());
        for (int i = 0; i + 1 < chunks.size(); i++) {
            ChunkOut a = chunks.get(i), b = chunks.get(i + 1);
            assertThat(tokens.count(b.start(), a.end())).isEqualTo(overlap);
        }
    }

    // ------------------------------------------------------------ recursive

    @Property(tries = 150)
    void recursiveChunksAreSoundBoundedAndStayInSections(@ForAll("docs") ParsedDocument doc,
                                                         @ForAll @IntRange(min = 6, max = 80) int max,
                                                         @ForAll boolean prepend) {
        List<ChunkOut> chunks = new RecursiveChunker(max, prepend).chunk(doc);
        assertSound(doc, chunks, !prepend || doc.sections().stream().allMatch(s -> s.headingPath().isEmpty()));
        assertThat(chunks).allSatisfy(c -> assertThat(c.tokenCount()).isLessThanOrEqualTo(max));
        assertWithinSections(doc, chunks);
    }

    // ------------------------------------------------------------ structure

    @Property(tries = 150)
    void structureChunksAreSoundAndNeverSplitListItems(@ForAll("docs") ParsedDocument doc,
                                                       @ForAll @IntRange(min = 6, max = 80) int max) {
        List<ChunkOut> chunks = new StructureChunker(max).chunk(doc);
        assertSound(doc, chunks, false);
        assertWithinSections(doc, chunks);
        for (ChunkOut c : chunks) {
            if (c.tokenCount() > max) {
                // Only an indivisible block may exceed the limit, and then it is alone in its chunk.
                Block only = doc.blocks().stream().filter(b -> b.start() == c.start() && b.end() == c.end()).findFirst().orElse(null);
                assertThat(only).as("oversize chunk must be a single block").isNotNull();
                assertThat(only.kind()).isIn(Kind.LIST_ITEM, Kind.TABLE_ROW);
            }
        }
        // List items and table rows are never cut: each lies wholly inside one chunk.
        for (Block b : doc.blocks()) {
            if (b.kind() == Kind.LIST_ITEM || b.kind() == Kind.TABLE_ROW) {
                assertThat(chunks).as("block [%d,%d) is whole", b.start(), b.end())
                        .anyMatch(c -> c.start() <= b.start() && b.end() <= c.end());
            }
        }
        // Every chunk carries its heading path, except that a chunk which already begins with its own heading does not repeat it.
        assertThat(chunks).allSatisfy(c -> {
            String body = doc.text().substring(c.start(), c.end());
            List<String> path = doc.sections().get(c.sectionOrdinal()).headingPath();
            if (!path.isEmpty() && body.startsWith(path.get(path.size() - 1))) path = path.subList(0, path.size() - 1);
            if (path.isEmpty()) assertThat(c.text()).isEqualTo(body);
            else assertThat(c.text()).startsWith(String.join(" > ", path) + "\n").endsWith(body);
        });
    }

    // ------------------------------------------------------------ parent_child

    @Property(tries = 150)
    void parentChildChunksNestAndChildrenCoverEveryParent(@ForAll("docs") ParsedDocument doc,
                                                          @ForAll @IntRange(min = 12, max = 80) int parent,
                                                          @ForAll @IntRange(min = 3, max = 11) int child) {
        List<ChunkOut> chunks = new ParentChildChunker(parent, child).chunk(doc);
        TokenIndex tokens = new TokenIndex(doc.text());
        for (int i = 0; i < chunks.size(); i++) {
            ChunkOut c = chunks.get(i);
            assertThat(c.ordinal()).isEqualTo(i);
            if (c.role() == Chunker.Role.PARENT) {
                assertThat(c.parentOrdinal()).isNull();
                assertThat(c.tokenCount()).isLessThanOrEqualTo(parent);
            } else {
                assertThat(c.role()).isEqualTo(Chunker.Role.CHILD);
                assertThat(c.parentOrdinal()).isNotNull().isLessThan(i);
                ChunkOut p = chunks.get(c.parentOrdinal());
                assertThat(p.role()).isEqualTo(Chunker.Role.PARENT);
                assertThat(c.start()).isGreaterThanOrEqualTo(p.start());
                assertThat(c.end()).isLessThanOrEqualTo(p.end());
                assertThat(c.tokenCount()).isLessThanOrEqualTo(child);
            }
        }
        // Every token of the document is in some child, so nothing is unsearchable.
        List<ChunkOut> children = chunks.stream().filter(c -> c.role() == Chunker.Role.CHILD).toList();
        for (int t = 0; t < tokens.size(); t++) {
            int ts = tokens.start(t), te = tokens.end(t);
            assertThat(children).as("token %d is covered by a child", t).anyMatch(c -> c.start() <= ts && te <= c.end());
        }
    }

    // ------------------------------------------------------------ examples

    @Example
    void structureKeepsASmallSectionAsOneChunk() {
        TextBuilder tb = new TextBuilder();
        tb.heading(1, "Policy");
        tb.block(Kind.PARAGRAPH, "First paragraph here.");
        tb.block(Kind.LIST_ITEM, "an item");
        ParsedDocument doc = tb.build();
        List<ChunkOut> chunks = new StructureChunker(100).chunk(doc);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo("Policy\n\nFirst paragraph here.\n\nan item");
    }

    @Example
    void emptyDocumentsProduceNoChunks() {
        ParsedDocument empty = new TextBuilder().build();
        assertThat(new FixedChunker(10, 0).chunk(empty)).isEmpty();
        assertThat(new RecursiveChunker(10, true).chunk(empty)).isEmpty();
        assertThat(new StructureChunker(10).chunk(empty)).isEmpty();
    }

    @Example
    void createFromStoredConfig() {
        assertThat(Chunker.create("fixed", java.util.Map.of("size", 256, "overlap", 25))).isInstanceOf(FixedChunker.class);
        assertThat(Chunker.create("recursive", java.util.Map.of("maxSize", 512, "prependHeadingPath", false))).isInstanceOf(RecursiveChunker.class);
        assertThat(Chunker.create("structure", java.util.Map.of("maxSize", 512))).isInstanceOf(StructureChunker.class);
        assertThat(Chunker.create("parent_child", java.util.Map.of("parentSize", 1024, "childSize", 256))).isInstanceOf(ParentChildChunker.class);
    }
}
