package dev.glassbox.dataset;

import java.util.UUID;

/**
 * Gold evidence for a question: a character range of one document version's normalized text (never a chunk id, so it stays
 * valid when the chunking changes). {@code text} is a snippet kept for display and for the answerability check.
 */
public record GoldSpan(UUID documentId, UUID versionId, int charStart, int charEnd, String text) {
    public static final int SNIPPET_CHARS = 600;

    public static String snippet(String documentText, int start, int end) {
        String t = documentText.substring(start, end);
        return t.length() <= SNIPPET_CHARS ? t : t.substring(0, SNIPPET_CHARS) + "...";
    }
}
