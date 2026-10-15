package dev.glassbox.retrieval.transform;

import java.util.List;
import java.util.UUID;

/** Turns the user's question into what is actually searched. */
public interface QueryTransformer {
    /**
     * What to search: {@code keyword} goes to the full-text side, {@code embed} is what gets embedded for the vector side. They differ
     * for HyDE, which embeds a hypothetical answer but still matches the question's own words.
     */
    record Search(String keyword, String embed) {
        public static Search of(String query) {
            return new Search(query, query);
        }
    }

    /** {@code searches} always starts with something to search (the original question when the model's reply was unusable). */
    record Plan(List<Search> searches, int llmCalls, int tokens) {
        public static Plan unchanged(String query) {
            return new Plan(List.of(Search.of(query)), 0, 0);
        }
    }

    Transformer type();

    Plan transform(UUID workspaceId, String query);
}
