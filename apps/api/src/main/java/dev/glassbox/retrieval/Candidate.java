package dev.glassbox.retrieval;

import java.util.UUID;

/**
 * One retrieved chunk with everything X-Ray needs to explain why it ranked where it did.
 * Rank and score fields are null when that retriever did not return the chunk.
 */
public record Candidate(UUID chunkId, UUID documentId, String text, int tokenCount, int charStart, int charEnd, Integer page,
                        double fusedScore, Integer vecRank, Double vecDistance, Integer kwRank, Double kwScore,
                        Double rerankScore) {

    public Candidate withRerankScore(double score) {
        return new Candidate(chunkId, documentId, text, tokenCount, charStart, charEnd, page, fusedScore, vecRank, vecDistance,
                kwRank, kwScore, score);
    }

    /** Overlap of the two spans in characters; 0 when they are in different documents. */
    public int overlapWith(Candidate other) {
        if (!documentId.equals(other.documentId)) return 0;
        return Math.max(0, Math.min(charEnd, other.charEnd) - Math.max(charStart, other.charStart));
    }
}
