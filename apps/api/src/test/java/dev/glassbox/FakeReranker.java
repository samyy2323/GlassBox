package dev.glassbox;

import dev.glassbox.rerank.Reranker;
import dev.glassbox.retrieval.Candidate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Stands in for the sidecar cross-encoder in Spring tests: score = number of query words found in the chunk. */
@Component
public class FakeReranker implements Reranker {
    @Override public Type type() { return Type.CROSS_ENCODER; }

    @Override
    public List<Candidate> rerank(String query, List<Candidate> candidates, int topN) {
        Set<String> words = Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\W+")).filter(w -> !w.isBlank()).collect(Collectors.toSet());
        List<Candidate> scored = new ArrayList<>();
        for (Candidate c : candidates) {
            String text = c.text().toLowerCase(Locale.ROOT);
            scored.add(c.withRerankScore(words.stream().filter(text::contains).count()));
        }
        scored.sort(Comparator.comparingDouble((Candidate c) -> c.rerankScore()).reversed());
        return scored.subList(0, Math.min(topN, scored.size()));
    }
}
