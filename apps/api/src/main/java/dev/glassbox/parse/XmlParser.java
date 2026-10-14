package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/**
 * Generic XML: every element that carries its own text becomes "tag: text". Not for XHTML (that is HTML) or office files.
 * jsoup's XML parser does not load external entities or DTDs, so uploaded XML cannot reach the network or the disk.
 */
public class XmlParser implements DocumentParser {
    private static final int MAX_ELEMENTS = 200_000;

    @Override
    public ParsedDocument parse(byte[] data) {
        Document doc = Jsoup.parse(TextDecoding.decode(data), "", Parser.xmlParser());
        TextBuilder tb = new TextBuilder();
        int seen = 0;
        for (Element e : doc.getAllElements()) {
            if (++seen > MAX_ELEMENTS) {
                tb.block(Kind.PARAGRAPH, "Note: only the first " + MAX_ELEMENTS + " elements of this file were indexed.");
                break;
            }
            String own = e.ownText();
            if (own.isBlank()) continue;
            tb.block(Kind.LIST_ITEM, e.tagName() + ": " + own);
        }
        return DocumentParser.nonEmpty(tb, "The XML file");
    }
}
