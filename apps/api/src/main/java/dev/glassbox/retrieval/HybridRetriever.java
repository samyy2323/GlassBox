package dev.glassbox.retrieval;

import dev.glassbox.embed.EmbeddingService;
import dev.glassbox.tenancy.Tx;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Runs {@link HybridSql} for one workspace and chunking. Vector, keyword and hybrid are one query with a branch switched off. */
@Component
public class HybridRetriever {
    private final Tx tx;

    public HybridRetriever(Tx tx) {
        this.tx = tx;
    }

    public List<Candidate> search(UUID workspaceId, UUID chunkingId, String queryText, float[] queryVector, RetrievalParams p) {
        String sql = HybridSql.build(p.model());
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("qvec", EmbeddingService.literal(queryVector))
                .addValue("qtext", keywordQuery(queryText, p.keywordMatch()))
                .addValue("chunking", chunkingId)
                .addValue("k_vec", p.effectiveKVec())
                .addValue("k_kw", p.effectiveKKw())
                .addValue("k_fused", p.kFused())
                .addValue("w_vec", p.wVec())
                .addValue("w_kw", p.wKw())
                .addValue("rrf_k", p.rrfK());
        return tx.workspace(workspaceId, null, j -> {
            // Both are transaction-local. iterative_scan keeps scanning the HNSW graph until k rows survive the
            // tenant/chunking filters instead of returning whatever the first ef_search candidates happen to be.
            j.queryForObject("SELECT set_config('hnsw.ef_search', ?, true)", String.class, String.valueOf(p.efSearch()));
            j.queryForObject("SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true)", String.class);
            return new NamedParameterJdbcTemplate(j).query(sql, args, MAPPER);
        });
    }

    private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+");

    /** ANY: "how often pumps" becomes "how or often or pumps" (websearch syntax); ALL passes the text through unchanged. */
    static String keywordQuery(String text, RetrievalParams.KeywordMatch match) {
        if (match == RetrievalParams.KeywordMatch.ALL) return text;
        List<String> words = new java.util.ArrayList<>();
        var m = WORD.matcher(text);
        while (m.find()) {
            String w = m.group();
            if (!w.equalsIgnoreCase("or")) words.add(w); // a literal "or" in the question must not become an operator
        }
        return String.join(" or ", words);
    }

    static final RowMapper<Candidate> MAPPER = (rs, n) -> new Candidate(
            rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class), rs.getString("text"),
            rs.getInt("token_count"), rs.getInt("char_start"), rs.getInt("char_end"), (Integer) rs.getObject("page"),
            rs.getDouble("score"), (Integer) intOrNull(rs.getObject("vec_rank")), (Double) rs.getObject("vec_dist"),
            (Integer) intOrNull(rs.getObject("kw_rank")), kwScore(rs.getObject("kw_score")), null);

    /** row_number() is a bigint. */
    private static Integer intOrNull(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static Double kwScore(Object o) {
        return o == null ? null : ((Number) o).doubleValue();
    }
}
