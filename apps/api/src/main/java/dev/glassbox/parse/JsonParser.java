package dev.glassbox.parse;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.glassbox.parse.ParsedDocument.Kind;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * JSON and JSON Lines. Leaves become "path: value" so a search can match the key and the value together. A top-level object
 * gets a heading per key; an array of records gets one row per element.
 */
public class JsonParser implements DocumentParser {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LEAVES = 200_000;

    @Override
    public ParsedDocument parse(byte[] data) throws IOException {
        TextBuilder tb = new TextBuilder();
        int[] budget = {MAX_LEAVES};
        List<JsonNode> roots = new ArrayList<>();
        // Read value by value: MappingIterator would silently unwrap a top-level array into its elements.
        try (com.fasterxml.jackson.core.JsonParser p = MAPPER.getFactory().createParser(TextDecoding.decode(data))) {
            while (p.nextToken() != null && roots.size() < MAX_LEAVES) roots.add(MAPPER.readTree(p));
        } catch (JsonProcessingException e) {
            throw new UnsupportedDocumentException("The file is not valid JSON: " + e.getOriginalMessage());
        }
        if (roots.size() > 1) { // JSON Lines: each line is a record
            for (JsonNode record : roots) {
                if (budget[0] <= 0) break;
                record(tb, record, budget);
            }
        } else if (roots.size() == 1) {
            root(tb, roots.get(0), budget);
        }
        if (budget[0] <= 0) tb.block(Kind.PARAGRAPH, "Note: only the first " + MAX_LEAVES + " values of this file were indexed.");
        return DocumentParser.nonEmpty(tb, "The JSON file");
    }

    private void record(TextBuilder tb, JsonNode node, int[] budget) {
        List<String> lines = new ArrayList<>();
        flatten(node, "", lines, budget);
        if (!lines.isEmpty()) tb.block(Kind.TABLE_ROW, String.join("; ", lines));
    }

    private void root(TextBuilder tb, JsonNode node, int[] budget) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext() && budget[0] > 0) {
                var e = it.next();
                if (e.getValue().isContainerNode()) {
                    tb.heading(1, e.getKey());
                    List<String> lines = new ArrayList<>();
                    flatten(e.getValue(), e.getKey(), lines, budget);
                    lines.forEach(l -> tb.block(Kind.LIST_ITEM, l));
                } else if (!e.getValue().isNull()) {
                    tb.block(Kind.LIST_ITEM, e.getKey() + ": " + e.getValue().asText());
                    budget[0]--;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                if (budget[0] <= 0) break;
                record(tb, element, budget);
            }
        } else if (!node.isNull()) {
            tb.block(Kind.PARAGRAPH, node.asText());
            budget[0]--;
        }
    }

    private void flatten(JsonNode n, String path, List<String> out, int[] budget) {
        if (budget[0] <= 0) return;
        if (n.isObject()) {
            n.fields().forEachRemaining(e -> flatten(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), out, budget));
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) flatten(n.get(i), path + "[" + i + "]", out, budget);
        } else if (!n.isNull()) {
            out.add(path.isEmpty() ? n.asText() : path + ": " + n.asText());
            budget[0]--;
        }
    }
}
