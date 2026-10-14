package dev.glassbox.generate;

import dev.glassbox.retrieval.ContextBuilder.Context;
import dev.glassbox.retrieval.ContextBuilder.ContextChunk;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Maps [c1] style labels in an answer back to the context chunks they point at. */
@Component
public class CitationParser {
    public record Citation(String label, UUID chunkId, UUID documentId, String title, Integer page, int charStart, int charEnd, String text) {}

    /** {@code unknown} are labels the model used that are not in the context: invented citations. */
    public record Parsed(List<Citation> citations, List<String> unknown) {}

    private static final Pattern LABELS = Pattern.compile("\\[(c\\d+(?:\\s*,\\s*c\\d+)*)\\]");

    public Parsed parse(String answer, Context context, Map<UUID, String> titles) {
        Map<String, ContextChunk> byLabel = new LinkedHashMap<>();
        context.included().forEach(c -> byLabel.put(c.label(), c));
        Set<String> used = new LinkedHashSet<>();
        Matcher m = LABELS.matcher(answer);
        while (m.find()) {
            for (String l : m.group(1).split("\\s*,\\s*")) used.add(l);
        }
        List<Citation> citations = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String label : used) {
            ContextChunk c = byLabel.get(label);
            if (c == null) {
                unknown.add(label);
                continue;
            }
            var ch = c.chunk();
            citations.add(new Citation(label, ch.chunkId(), ch.documentId(), titles.getOrDefault(ch.documentId(), "document"),
                    ch.page(), ch.charStart(), ch.charEnd(), ch.text()));
        }
        return new Parsed(citations, unknown);
    }
}
