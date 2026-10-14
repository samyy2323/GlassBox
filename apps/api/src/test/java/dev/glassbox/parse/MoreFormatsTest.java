package dev.glassbox.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.glassbox.MoreTestFiles;
import dev.glassbox.TestFiles;
import dev.glassbox.parse.ParsedDocument.Block;
import dev.glassbox.parse.ParsedDocument.Kind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Each added format family: what gets extracted, what is skipped, and how type detection decides. */
class MoreFormatsTest {
    private static String slice(ParsedDocument d, Block b) {
        return d.text().substring(b.start(), b.end());
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ text and encodings

    @Test
    void plainTextIsSplitIntoParagraphsAndDecodedByEncoding() throws Exception {
        ParsedDocument d = new TextParser().parse(utf8("First paragraph.\n\nSecond paragraph\nstill second.\r\n\r\nThird."));
        assertThat(d.blocks()).hasSize(3);
        assertThat(slice(d, d.blocks().get(1))).isEqualTo("Second paragraph still second.");

        byte[] latin = "Café au lait: crème brûlée".getBytes(java.nio.charset.Charset.forName("windows-1252"));
        assertThat(new TextParser().parse(latin).text()).isEqualTo("Café au lait: crème brûlée");

        byte[] utf16 = new byte[] {(byte) 0xFF, (byte) 0xFE};
        byte[] body = "Grüße from UTF-16".getBytes(StandardCharsets.UTF_16LE);
        byte[] both = new byte[utf16.length + body.length];
        System.arraycopy(utf16, 0, both, 0, 2);
        System.arraycopy(body, 0, both, 2, body.length);
        assertThat(new TextParser().parse(both).text()).isEqualTo("Grüße from UTF-16");
        assertThat(new TextParser().parse(("﻿with bom").getBytes(StandardCharsets.UTF_8)).text()).isEqualTo("with bom");
    }

    @Test
    void emptyOrBlankTextIsUnsupportedNotACrash() {
        assertThatThrownBy(() -> new TextParser().parse(utf8("  \n\n \t "))).isInstanceOf(UnsupportedDocumentException.class);
    }

    // ------------------------------------------------------------ tables and data

    @Test
    void csvRowsReadAsHeaderValuePairsWithQuotingAndDelimiterDetection() throws Exception {
        ParsedDocument d = new CsvParser().parse(utf8("Plan,Price,Note\nBasic,10,\"cheap, but \"\"basic\"\"\"\nTeam,40,\n,,\n"));
        assertThat(d.blocks()).extracting(b -> slice(d, b)).containsExactly(
                "Plan: Basic; Price: 10; Note: cheap, but \"basic\"", "Plan: Team; Price: 40");
        assertThat(d.blocks()).allSatisfy(b -> assertThat(b.kind()).isEqualTo(Kind.TABLE_ROW));

        assertThat(new CsvParser().parse(utf8("Name\tAge\nAda\t36\n")).text()).isEqualTo("Name: Ada; Age: 36");
        assertThat(new CsvParser().parse(utf8("Name;Age\nAda;36\n")).text()).isEqualTo("Name: Ada; Age: 36");
    }

    @Test
    void aTableWithoutAUsableHeaderFallsBackToPlainRows() throws Exception {
        assertThat(new CsvParser().parse(utf8("1,2,3\n4,5,6\n")).blocks()).extracting(b -> b.kind()).containsOnly(Kind.TABLE_ROW);
        assertThat(new CsvParser().parse(utf8("1,2,3\n4,5,6\n")).text()).isEqualTo("1 | 2 | 3\n\n4 | 5 | 6");
    }

    @Test
    void jsonObjectsArraysAndJsonLines() throws Exception {
        ParsedDocument obj = new JsonParser().parse(utf8("{\"service\":\"billing\",\"limits\":{\"rate\":100,\"burst\":[1,2]},\"off\":null}"));
        assertThat(obj.text()).contains("service: billing").contains("limits.rate: 100").contains("limits.burst[1]: 2").doesNotContain("off");
        assertThat(obj.blocks().stream().filter(b -> b.kind() == Kind.HEADING).map(b -> slice(obj, b))).containsExactly("limits");

        ParsedDocument arr = new JsonParser().parse(utf8("[{\"id\":1,\"name\":\"Ada\"},{\"id\":2,\"name\":\"Lin\"}]"));
        assertThat(arr.blocks()).extracting(b -> slice(arr, b)).containsExactly("id: 1; name: Ada", "id: 2; name: Lin");

        ParsedDocument lines = new JsonParser().parse(utf8("{\"a\":1}\n{\"a\":2}\n"));
        assertThat(lines.text()).contains("a: 1").contains("a: 2");

        assertThatThrownBy(() -> new JsonParser().parse(utf8("{not json"))).isInstanceOf(UnsupportedDocumentException.class).hasMessageContaining("not valid JSON");
    }

    @Test
    void xmlElementsWithTextBecomeTagValueLinesAndEntitiesAreNotResolved() throws Exception {
        ParsedDocument d = new XmlParser().parse(utf8("<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<config><name>router</name><owner>&e;</owner><ports><p>80</p></ports></config>"));
        assertThat(d.text()).contains("name: router").contains("p: 80").doesNotContain("root:");
    }

    // ------------------------------------------------------------ office formats

    @Test
    void xlsxAndLegacyXlsReadSheetsAsRowsEvaluateFormulasAndSkipHiddenSheets() throws Exception {
        for (byte[] file : List.of(MoreTestFiles.xlsx(), MoreTestFiles.xls())) {
            ParsedDocument d = new SpreadsheetParser().parse(file);
            assertThat(d.blocks().stream().filter(b -> b.kind() == Kind.HEADING).map(b -> slice(d, b))).containsExactly("Prices", "Notes");
            assertThat(d.text()).contains("Plan: Basic; Price: 10; Seats: 1").contains("Plan: Team; Price: 40; Seats: 5")
                    .contains("Plan: Total; Price: 50").contains("Prices exclude VAT").doesNotContain("hidden sheet text");
        }
    }

    @Test
    void pptxAndLegacyPptReadSlideTextWithTitles() throws Exception {
        ParsedDocument d = new PresentationParser().parse(MoreTestFiles.pptx());
        assertThat(d.blocks().stream().filter(b -> b.kind() == Kind.HEADING).map(b -> slice(d, b))).hasSize(2).allMatch(h -> h.startsWith("Slide "));
        assertThat(d.text()).contains("Quarterly safety review").contains("Incidents fell by 40 percent").contains("Second slide text");

        ParsedDocument legacy = new PresentationParser().parse(MoreTestFiles.ppt());
        assertThat(legacy.text()).contains("Legacy slide about forklift training");
    }

    @Test
    void notAnOfficeFileIsReportedNotThrownAsACrash() {
        assertThatThrownBy(() -> new SpreadsheetParser().parse(utf8("this is not a workbook"))).isNotNull();
        assertThatThrownBy(() -> new LegacyDocParser().parse(utf8("this is not a word file"))).isNotNull();
    }

    @Test
    void rtfTextIsExtractedWithoutControlWords() throws Exception {
        ParsedDocument d = new RtfParser().parse(utf8(MoreTestFiles.RTF));
        assertThat(d.text()).contains("Boilers are checked every autumn.").contains("Second paragraph about chimneys.").doesNotContain("rtf1").doesNotContain("\\par");
    }

    @Test
    void openDocumentHeadingsListsAndTables() throws Exception {
        ParsedDocument d = new OdfParser().parse(MoreTestFiles.odt());
        assertThat(d.blocks().get(0).kind()).isEqualTo(Kind.HEADING);
        assertThat(slice(d, d.blocks().get(0))).isEqualTo("Maintenance handbook");
        assertThat(d.text()).contains("Valves are serviced every six months.").contains("Engineer | Dana");
        assertThat(d.blocks().stream().filter(b -> b.kind() == Kind.LIST_ITEM).map(b -> slice(d, b))).containsExactly("Close the supply line");
    }

    @Test
    void epubChaptersFollowTheSpineNotTheFileNames() throws Exception {
        ParsedDocument d = new EpubParser().parse(MoreTestFiles.epub());
        assertThat(d.text().indexOf("Chapter One")).isGreaterThanOrEqualTo(0).isLessThan(d.text().indexOf("Chapter Two"));
        assertThat(d.text()).contains("It begins in the harbour.").contains("It ends at the lighthouse.");
    }

    // ------------------------------------------------------------ safety

    @Test
    void anOversizedEntryInsideAnArchiveIsRefusedNotInflatedIntoMemory() {
        byte[] bomb = MoreTestFiles.zip(Map.of("content.xml", "x".repeat(((int) Zips.MAX_ENTRY_BYTES) + 1024)));
        assertThat(bomb.length).as("the bomb itself is tiny").isLessThan(200_000);
        assertThatThrownBy(() -> new OdfParser().parse(bomb)).isInstanceOf(UnsupportedDocumentException.class).hasMessageContaining("too large");
    }

    @Test
    void anOdfFileWithoutContentIsRejected() {
        assertThatThrownBy(() -> new OdfParser().parse(MoreTestFiles.zip(Map.of("mimetype", "x")))).isInstanceOf(UnsupportedDocumentException.class);
    }

    // ------------------------------------------------------------ type detection

    private static Optional<String> kind(String name, byte[] data) {
        return FileTypes.classify(name, data);
    }

    @Test
    void contentBeatsTheFileNameWhenTheContentIsUnambiguous() {
        assertThat(kind("report.txt", TestFiles.pdf("hello world"))).contains("PDF");
        assertThat(kind("page.txt", utf8("<html><body><p>hi</p></body></html>"))).contains("HTML");
        assertThat(kind("sheet.bin", MoreTestFiles.xlsx())).contains("XLSX");
        assertThat(kind("legacy.dat", MoreTestFiles.xls())).contains("XLS");
    }

    @Test
    void containersAndPlainTextAreClassifiedByExtensionWhenContentIsGeneric() {
        assertThat(kind("deck.pptx", MoreTestFiles.pptx())).contains("PPTX");
        assertThat(kind("book.epub", MoreTestFiles.epub())).contains("EPUB");
        assertThat(kind("notes.odt", MoreTestFiles.odt())).contains("ODF");
        assertThat(kind("memo.docx", TestFiles.docx())).contains("DOCX");
        assertThat(kind("data.csv", utf8("a,b\n1,2\n"))).contains("CSV");
        assertThat(kind("data.tsv", utf8("a\tb\n1\t2\n"))).contains("CSV");
        assertThat(kind("conf.json", utf8("{\"a\":1}"))).contains("JSON");
        assertThat(kind("feed.xml", utf8("<a><b>1</b></a>"))).contains("XML");
        assertThat(kind("README.md", utf8("# Title\n\ntext"))).contains("MD");
        assertThat(kind("memo.rtf", utf8(MoreTestFiles.RTF))).contains("RTF");
        assertThat(kind("notes.txt", utf8("just text"))).contains("TXT");
        assertThat(kind("Main.java", utf8("class Main { void run() {} }"))).contains("TXT");
        assertThat(kind("app.log", utf8("2026-10-14 ERROR something broke"))).contains("TXT");
        assertThat(kind("Makefile", utf8("all:\n\tgcc main.c\n"))).as("no extension, but plainly text").contains("TXT");
    }

    @Test
    void mediaThatNeedsOcrOrTranscriptionIsRefusedWithAReason() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 'I', 'H', 'D', 'R'};
        assertThatThrownBy(() -> kind("scan.png", png)).isInstanceOf(FileTypes.NotSupported.class).hasMessageContaining("OCR");
        byte[] mp3 = {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0x21, 0x54, 'I', '2', 0, 0, 0, 0, 0, 0, 0};
        assertThatThrownBy(() -> kind("talk.mp3", mp3)).isInstanceOf(FileTypes.NotSupported.class).hasMessageContaining("transcription");
        assertThatThrownBy(() -> kind("bundle.zip", MoreTestFiles.zip(Map.of("a.txt", "x")))).isInstanceOf(FileTypes.NotSupported.class).hasMessageContaining("Archives");
    }

    @Test
    void unrecognisedBinaryIsNotClassified() {
        byte[] noise = new byte[2000];
        new java.util.Random(7).nextBytes(noise);
        noise[10] = 0;
        assertThat(kind("mystery.xyz", noise)).isEmpty();
        assertThat(kind("empty.txt", new byte[0])).isEmpty();
    }
}
