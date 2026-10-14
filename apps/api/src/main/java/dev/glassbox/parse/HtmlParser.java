package dev.glassbox.parse;

import dev.glassbox.parse.ParsedDocument.Kind;
import java.nio.charset.StandardCharsets;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

public class HtmlParser implements DocumentParser {
    private static final String BLOCKS = "h1,h2,h3,h4,h5,h6,p,li,tr,pre,blockquote,dt,dd";

    @Override
    public ParsedDocument parse(byte[] data) {
        Document doc = Jsoup.parse(new String(data, StandardCharsets.UTF_8));
        doc.select("script,style,noscript,template,nav,footer").remove();
        TextBuilder tb = new TextBuilder();
        for (Element e : doc.select(BLOCKS)) {
            // Take the outermost block only (a <p> inside an <li> is part of the list item).
            if (e.parents().stream().anyMatch(p -> p.is(BLOCKS))) continue;
            String tag = e.tagName();
            if (tag.matches("h[1-6]")) {
                tb.heading(tag.charAt(1) - '0', e.text());
            } else {
                tb.block(switch (tag) {
                    case "li", "dt", "dd" -> Kind.LIST_ITEM;
                    case "tr" -> Kind.TABLE_ROW;
                    default -> Kind.PARAGRAPH;
                }, tag.equals("tr") ? String.join(" | ", e.select("th,td").eachText()) : e.text());
            }
        }
        return DocumentParser.nonEmpty(tb, "HTML");
    }
}
