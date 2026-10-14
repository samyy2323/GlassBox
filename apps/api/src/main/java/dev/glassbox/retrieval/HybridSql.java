package dev.glassbox.retrieval;

import java.util.Map;

/**
 * The hybrid retrieval statement from SPEC 6: vector and keyword rankings fused with Reciprocal Rank Fusion
 * inside Postgres.
 *
 * Differences from the spec text, both deliberate:
 *  - the vector branch reads only {@code embeddings}; tenant (RLS) and chunking filters sit in the same index-scan
 *    node, so pgvector's iterative scan can keep going until it has k rows (a join filter would run after the scan
 *    and silently return fewer than k);
 *  - extra columns (distance, keyword score) and a final join to {@code chunks} so X-Ray gets everything at once.
 *
 * The model id is interpolated, not bound, for two reasons: the partial HNSW index only matches when the planner can
 * see {@code model = '...'} as a constant (a bound parameter breaks that once a generic plan is used), and the
 * dimension in the cast is part of the index expression. Only ids from {@link #MODEL_DIMS} are accepted.
 */
public final class HybridSql {
    /** Models that have a partial HNSW index (migration V3) and their dimension. */
    public static final Map<String, Integer> MODEL_DIMS = Map.of(
            "bge-small-en-v1.5", 384,
            "bge-base-en-v1.5", 768,
            "all-MiniLM-L6-v2", 384,
            "text-embedding-3-small", 1536);

    private HybridSql() {}

    public static String build(String model) {
        Integer dim = MODEL_DIMS.get(model);
        if (dim == null) throw new IllegalArgumentException("Unknown embedding model: " + model);
        return """
                WITH vec AS (
                  SELECT id, dist, row_number() OVER (ORDER BY dist) AS rnk FROM (
                    SELECT e.chunk_id AS id, e.vector::vector(%1$d) <=> CAST(:qvec AS vector(%1$d)) AS dist
                    FROM embeddings e
                    WHERE e.model = '%2$s' AND e.chunking_id = :chunking
                    ORDER BY e.vector::vector(%1$d) <=> CAST(:qvec AS vector(%1$d))
                    LIMIT :k_vec) s
                ), kw AS (
                  SELECT id, score, row_number() OVER (ORDER BY score DESC) AS rnk FROM (
                    SELECT c.id, ts_rank_cd(c.tsv, q) AS score
                    FROM chunks c, websearch_to_tsquery('english', :qtext) q
                    WHERE c.chunking_id = :chunking AND NOT c.superseded AND c.tsv @@ q
                    ORDER BY score DESC LIMIT :k_kw) s
                ), fused AS (
                  SELECT COALESCE(vec.id, kw.id) AS id,
                         COALESCE(:w_vec / (:rrf_k + vec.rnk), 0) + COALESCE(:w_kw / (:rrf_k + kw.rnk), 0) AS score,
                         vec.rnk AS vec_rank, vec.dist AS vec_dist, kw.rnk AS kw_rank, kw.score AS kw_score
                  FROM vec FULL OUTER JOIN kw ON vec.id = kw.id
                  ORDER BY score DESC, COALESCE(vec.id, kw.id)
                  LIMIT :k_fused)
                SELECT f.id, f.score, f.vec_rank, f.vec_dist, f.kw_rank, f.kw_score,
                       c.document_id, c.text, c.token_count, c.char_start, c.char_end, c.page
                FROM fused f JOIN chunks c ON c.id = f.id
                ORDER BY f.score DESC, f.id
                """.formatted(dim, model);
    }
}
