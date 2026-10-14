package dev.glassbox.retrieval;

/**
 * Tunables of the retrieval stage (SPEC 6). All of them are part of the future PipelineConfig and therefore of the
 * AutoTune search space.
 */
public record RetrievalParams(Mode mode, String model, int kVec, int kKw, int rrfK, double wVec, double wKw, int kFused,
                              int efSearch, KeywordMatch keywordMatch) {

    public enum Mode { VECTOR, KEYWORD, HYBRID }

    /**
     * How question words combine in the full-text query. {@code websearch_to_tsquery} alone means ALL: every word must
     * appear in a chunk, so a natural question ("how often are pumps inspected") matches nothing unless the chunk also
     * contains "often". ANY ORs the words and lets {@code ts_rank_cd} rank by how many matched.
     */
    public enum KeywordMatch { ALL, ANY }

    public static RetrievalParams hybrid(String model) {
        return new RetrievalParams(Mode.HYBRID, model, 50, 50, 60, 1.0, 1.0, 50, 100, KeywordMatch.ANY);
    }

    public RetrievalParams withMode(Mode m) {
        return new RetrievalParams(m, model, kVec, kKw, rrfK, wVec, wKw, kFused, efSearch, keywordMatch);
    }

    public RetrievalParams withKeywordMatch(KeywordMatch k) {
        return new RetrievalParams(mode, model, kVec, kKw, rrfK, wVec, wKw, kFused, efSearch, k);
    }

    /** A disabled branch is switched off with LIMIT 0 so its index is never touched. */
    public int effectiveKVec() {
        return mode == Mode.KEYWORD ? 0 : kVec;
    }

    public int effectiveKKw() {
        return mode == Mode.VECTOR ? 0 : kKw;
    }
}
