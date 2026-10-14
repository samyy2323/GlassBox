package dev.glassbox.parse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/** EPUB e-books: the XHTML chapters in reading (spine) order, each read by the HTML parser. */
public class EpubParser implements DocumentParser {
    @Override
    public ParsedDocument parse(byte[] data) throws IOException {
        Map<String, byte[]> files = Zips.read(data, n -> n.endsWith(".opf") || n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm")
                || n.equals("META-INF/container.xml"));
        TextBuilder tb = new TextBuilder();
        for (String chapter : readingOrder(files)) {
            byte[] html = files.get(chapter);
            if (html != null) HtmlParser.appendTo(tb, new String(html, StandardCharsets.UTF_8));
        }
        return DocumentParser.nonEmpty(tb, "The e-book");
    }

    /** Spine order from the package document; if the book is malformed, every chapter file in name order. */
    private static List<String> readingOrder(Map<String, byte[]> files) {
        try {
            byte[] container = files.get("META-INF/container.xml");
            if (container != null) {
                Document c = Jsoup.parse(new String(container, StandardCharsets.UTF_8), "", Parser.xmlParser());
                Element rootfile = c.selectFirst("rootfile");
                String opfPath = rootfile == null ? null : rootfile.attr("full-path");
                byte[] opfBytes = opfPath == null ? null : files.get(opfPath);
                if (opfBytes != null) {
                    Document opf = Jsoup.parse(new String(opfBytes, StandardCharsets.UTF_8), "", Parser.xmlParser());
                    Map<String, String> hrefById = new HashMap<>();
                    for (Element item : opf.select("manifest > item")) hrefById.put(item.attr("id"), item.attr("href"));
                    String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
                    List<String> order = new ArrayList<>();
                    for (Element ref : opf.select("spine > itemref")) {
                        String href = hrefById.get(ref.attr("idref"));
                        if (href != null) order.add(URI.create("/" + base + href.replace(" ", "%20")).normalize().getPath().substring(1));
                    }
                    if (!order.isEmpty()) return order;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to the fallback below
        }
        return files.keySet().stream().filter(n -> !n.endsWith(".opf") && !n.endsWith("container.xml")).sorted().toList();
    }
}
