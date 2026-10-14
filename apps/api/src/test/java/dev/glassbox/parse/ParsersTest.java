package dev.glassbox.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.glassbox.TestFiles;
import dev.glassbox.parse.ParsedDocument.Block;
import dev.glassbox.parse.ParsedDocument.Kind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParsersTest {

    private static String slice(ParsedDocument d, Block b) {
        return d.text().substring(b.start(), b.end());
    }

    @Test
    void pdfKeepsPageNumbersAndOffsets() throws Exception {
        ParsedDocument d = new PdfParser().parse(TestFiles.pdf("Alpha first page.\n\nSecond paragraph here.", "Beta on page two."));
        assertThat(d.pageStarts()).hasSize(2);
        int alpha = d.text().indexOf("Alpha");
        int beta = d.text().indexOf("Beta");
        assertThat(d.pageAt(alpha)).isEqualTo(1);
        assertThat(d.pageAt(beta)).isEqualTo(2);
        assertThat(d.blocks()).allSatisfy(b -> assertThat(slice(d, b)).isNotBlank());
    }

    @Test
    void pdfWithoutTextLayerIsUnsupported() {
        assertThatThrownBy(() -> new PdfParser().parse(TestFiles.pdf("", "")))
                .isInstanceOf(UnsupportedDocumentException.class).hasMessageContaining("OCR");
    }

    @Test
    void passwordProtectedPdfIsUnsupported() {
        assertThatThrownBy(() -> new PdfParser().parse(TestFiles.encryptedPdf("secret", "hidden text")))
                .isInstanceOf(UnsupportedDocumentException.class).hasMessageContaining("Password");
    }

    @Test
    void docxHeadingsListsAndTables() throws Exception {
        ParsedDocument d = new DocxParser().parse(TestFiles.docx());
        List<Block> blocks = d.blocks();
        assertThat(blocks.get(0).kind()).isEqualTo(Kind.HEADING);
        assertThat(slice(d, blocks.get(0))).isEqualTo("Safety rules");
        Block goggles = blocks.stream().filter(b -> slice(d, b).startsWith("Goggles")).findFirst().orElseThrow();
        assertThat(goggles.kind()).isEqualTo(Kind.LIST_ITEM);
        assertThat(goggles.headingPath()).containsExactly("Safety rules", "Eyes");
        assertThat(d.text()).contains("Warehouse | 85 dB");
        assertThat(blocks.stream().filter(b -> b.kind() == Kind.TABLE_ROW)).hasSize(2);
    }

    @Test
    void htmlIgnoresScriptsAndFlattensTables() {
        ParsedDocument d = new HtmlParser().parse(TestFiles.HTML.getBytes(StandardCharsets.UTF_8));
        assertThat(d.text()).doesNotContain("alert");
        assertThat(d.text()).contains("Basic | 10");
        Block first = d.blocks().stream().filter(b -> slice(d, b).startsWith("First notice")).findFirst().orElseThrow();
        assertThat(first.kind()).isEqualTo(Kind.LIST_ITEM);
        assertThat(first.headingPath()).containsExactly("Billing", "Late fees");
        assertThat(d.sections()).extracting(ParsedDocument.Section::headingPath)
                .contains(List.of("Billing"), List.of("Billing", "Late fees"));
    }

    @Test
    void markdownHeadingsNestedListsAndTables() {
        ParsedDocument d = new MarkdownParser().parse(TestFiles.MARKDOWN.getBytes(StandardCharsets.UTF_8));
        assertThat(d.blocks().stream().filter(b -> b.kind() == Kind.LIST_ITEM).map(b -> slice(d, b)))
                .containsExactly("first step", "second step", "nested step");
        assertThat(d.text()).contains("Name | Value").contains("a | 1");
        assertThat(d.blocks().get(0).kind()).isEqualTo(Kind.HEADING);
    }

    @Test
    void sectionsNestByHeadingLevelAndCoverTheText() {
        ParsedDocument d = new MarkdownParser().parse("""
                preamble

                # A

                a text

                ## B

                b text

                # C

                c text
                """.getBytes(StandardCharsets.UTF_8));
        var sections = d.sections();
        assertThat(sections).extracting(s -> s.headingPath().toString())
                .containsExactly("[]", "[A]", "[A, B]", "[C]");
        assertThat(sections.get(2).parentOrdinal()).isEqualTo(1);
        assertThat(sections.get(3).parentOrdinal()).isNull();
        assertThat(sections.get(0).start()).isZero();
        assertThat(sections.get(sections.size() - 1).end()).isEqualTo(d.text().length());
        for (int i = 1; i < sections.size(); i++) assertThat(sections.get(i).start()).isEqualTo(sections.get(i - 1).end());
    }

    @Test
    void emptyInputsAreUnsupportedNotCrashes() {
        assertThatThrownBy(() -> new HtmlParser().parse("<html><body></body></html>".getBytes()))
                .isInstanceOf(UnsupportedDocumentException.class);
        assertThatThrownBy(() -> new MarkdownParser().parse("   \n".getBytes()))
                .isInstanceOf(UnsupportedDocumentException.class);
    }
}
