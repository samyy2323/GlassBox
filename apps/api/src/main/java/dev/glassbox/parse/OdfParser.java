package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/** OpenDocument text, spreadsheets and presentations (.odt .ods .odp): read from the package's content.xml. */
public class OdfParser implements DocumentParser {
    private static final int MAX_BLOCKS = 200_000;

    @Override
    public ParsedDocument parse(byte[] data) throws IOException {
        Map<String, byte[]> files = Zips.read(data, n -> n.equals("content.xml"));
        byte[] content = files.get("content.xml");
        if (content == null) throw new UnsupportedDocumentException("This is not a valid OpenDocument file");
        Element root = Jsoup.parse(new String(content, StandardCharsets.UTF_8), "", Parser.xmlParser());
        TextBuilder tb = new TextBuilder();
        walk(root, tb, new int[] {MAX_BLOCKS});
        return DocumentParser.nonEmpty(tb, "The OpenDocument file");
    }

    private void walk(Element e, TextBuilder tb, int[] budget) {
        for (Element child : e.children()) {
            if (budget[0] <= 0) return;
            switch (child.tagName()) {
                case "text:h" -> {
                    String level = child.attr("text:outline-level");
                    tb.heading(level.matches("\\d+") ? Integer.parseInt(level) : 1, child.text());
                    budget[0]--;
                }
                case "text:p" -> {
                    tb.block(Kind.PARAGRAPH, child.text());
                    budget[0]--;
                }
                case "text:list-item" -> {
                    // The item's own paragraphs; nested lists are items of their own.
                    String own = child.children().stream().filter(c -> c.tagName().equals("text:p") || c.tagName().equals("text:h"))
                            .map(Element::text).collect(Collectors.joining(" "));
                    tb.block(Kind.LIST_ITEM, own);
                    budget[0]--;
                    walk(child, tb, budget);
                }
                case "table:table" -> {
                    String name = child.attr("table:name");
                    if (!name.isBlank()) tb.heading(2, name);
                    walk(child, tb, budget);
                }
                case "table:table-row" -> {
                    String row = child.children().stream().map(Element::text).filter(t -> !t.isBlank()).collect(Collectors.joining(" | "));
                    tb.block(Kind.TABLE_ROW, row);
                    budget[0]--;
                }
                default -> walk(child, tb, budget);
            }
        }
    }
}
