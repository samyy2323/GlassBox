package dev.glassbox.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import dev.glassbox.TestDb;
import dev.glassbox.embed.EmbeddingService;
import dev.glassbox.tenancy.Tx;
import dev.glassbox.workspace.WorkspaceService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The hybrid SQL against a real pgvector, with RLS on. Rows are inserted directly so vectors are exactly controlled.
 */
@SpringBootTest
@ActiveProfiles("api")
class HybridRetrieverTest {
    static final String MODEL = "bge-small-en-v1.5";
    static final int DIM = 384;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        TestDb.register(r);
    }

    /**
     * The plan assertions depend on table statistics: with the thousands of vectors other test classes leave behind in the shared database the
     * planner prefers the chunking btree index (an exact search) to HNSW, and the "post-filtered ANN" pitfall cannot be shown at all. So this class
     * starts from empty chunk and embedding tables. Test classes do not run concurrently and each creates its own tenants.
     */
    @org.junit.jupiter.api.BeforeAll
    static void startFromEmptyIndexTables() throws java.sql.SQLException {
        try (var c = TestDb.ownerConnection(); var st = c.createStatement()) {
            st.execute("TRUNCATE embeddings, chunks CASCADE");
            st.execute("ANALYZE embeddings");
            st.execute("ANALYZE chunks");
        }
    }

    @Autowired Tx tx;
    @Autowired WorkspaceService workspaces;
    @Autowired HybridRetriever retriever;

    record Corpus(UUID ws, UUID chunking, UUID doc, UUID version) {}

    Corpus newCorpus() {
        UUID user = tx.anonymous(j -> j.queryForObject("SELECT auth_create_user(?, 'x')", UUID.class, "r-" + UUID.randomUUID() + "@example.com"));
        UUID ws = workspaces.create(user, "retrieval").id();
        UUID doc = UUID.randomUUID(), version = UUID.randomUUID(), chunking = UUID.randomUUID();
        tx.workspace(ws, null, j -> {
            j.update("INSERT INTO documents (id, workspace_id, title, source_type, content_hash, size_bytes) VALUES (?, ?, 't', 'MD', ?, 1)", doc, ws, UUID.randomUUID().toString());
            j.update("INSERT INTO document_versions (id, workspace_id, document_id, version, content_hash, storage_path) VALUES (?, ?, ?, 1, 'h', 'p')", version, ws, doc);
            j.update("INSERT INTO chunkings (id, workspace_id, strategy, params) VALUES (?, ?, 'recursive', ?::jsonb)", chunking, ws, "{\"t\":\"" + UUID.randomUUID() + "\"}");
            return null;
        });
        return new Corpus(ws, chunking, doc, version);
    }

    /** Adds one chunk plus its embedding. Returns the chunk id. */
    UUID chunk(Corpus c, int ordinal, String text, float[] vector) {
        UUID id = UUID.randomUUID();
        tx.workspace(c.ws(), null, j -> {
            j.update("INSERT INTO chunks (id, workspace_id, document_id, document_version_id, chunking_id, ordinal, text, token_count, char_start, char_end) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 10, ?, ?)", id, c.ws(), c.doc(), c.version(), c.chunking(), ordinal, text, ordinal * 1000, ordinal * 1000 + 500);
            j.update("INSERT INTO embeddings (chunk_id, workspace_id, model, dim, vector, content_hash, chunking_id) VALUES (?, ?, ?, ?, ?::vector, ?, ?)",
                    id, c.ws(), MODEL, DIM, EmbeddingService.literal(vector), UUID.randomUUID().toString(), c.chunking());
            return null;
        });
        return id;
    }

    /** Unit vector a*e0 + b*e_axis, with a^2 + b^2 = 1 so cosine distance to e0 is 1 - a. */
    static float[] mix(double a, int axis) {
        float[] v = new float[DIM];
        v[0] = (float) a;
        v[axis] = (float) Math.sqrt(1 - a * a);
        return v;
    }

    static float[] e0() {
        float[] v = new float[DIM];
        v[0] = 1f;
        return v;
    }

    // ------------------------------------------------------------ RRF

    @Test
    void reciprocalRankFusionCombinesVectorAndKeywordRanks() {
        Corpus c = newCorpus();
        UUID a = chunk(c, 0, "apples and oranges are fruit", e0());                               // vector rank 1, no keyword match
        UUID b = chunk(c, 1, "the pump is documented separately and an inspection happens much later", mix(0.8, 1)); // vec 2, kw weaker
        UUID cc = chunk(c, 2, "pump inspection", mix(0.5, 2));                                      // vec 3, kw best
        UUID d = chunk(c, 3, "bananas", mix(0.0, 3));                                               // vec 4, no keyword match

        // ALL-terms matching keeps the keyword ranking of the two matching chunks deterministic for this arithmetic check.
        List<Candidate> out = retriever.search(c.ws(), c.chunking(), "pump inspection", e0(),
                RetrievalParams.hybrid(MODEL).withKeywordMatch(RetrievalParams.KeywordMatch.ALL));

        assertThat(out).extracting(Candidate::chunkId).containsExactly(cc, b, a, d);
        // Whatever the ranks are, every score must equal the RRF formula applied to that candidate's own ranks.
        for (Candidate x : out) {
            double expected = (x.vecRank() == null ? 0 : 1.0 / (60 + x.vecRank())) + (x.kwRank() == null ? 0 : 1.0 / (60 + x.kwRank()));
            assertThat(x.fusedScore()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-9));
        }
        Map<UUID, Candidate> byId = new java.util.HashMap<>();
        out.forEach(x -> byId.put(x.chunkId(), x));
        assertThat(byId.get(a).vecRank()).isEqualTo(1);
        assertThat(byId.get(b).vecRank()).isEqualTo(2);
        assertThat(byId.get(cc).vecRank()).isEqualTo(3);
        assertThat(byId.get(d).vecRank()).isEqualTo(4);
        assertThat(byId.get(a).kwRank()).isNull();
        assertThat(byId.get(cc).kwRank()).isEqualTo(1);
        assertThat(byId.get(b).kwRank()).isEqualTo(2);
        assertThat(byId.get(a).vecDistance()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(byId.get(b).vecDistance()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-6));

        // score = w_vec/(k+vec_rank) + w_kw/(k+kw_rank), k = 60
        assertThat(byId.get(a).fusedScore()).isCloseTo(1.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(byId.get(b).fusedScore()).isCloseTo(1.0 / 62 + 1.0 / 62, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(byId.get(cc).fusedScore()).isCloseTo(1.0 / 63 + 1.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(byId.get(d).fusedScore()).isCloseTo(1.0 / 64, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void weightsAndRrfConstantChangeTheFusion() {
        Corpus c = newCorpus();
        UUID vectorFavourite = chunk(c, 0, "cats and dogs", e0());               // best vector rank, no keyword hit
        UUID keywordFavourite = chunk(c, 1, "pump inspection", mix(0.5, 1));     // keyword hit, worse vector rank
        RetrievalParams base = RetrievalParams.hybrid(MODEL);
        // Equal weights: the chunk found by both retrievers wins.
        assertThat(retriever.search(c.ws(), c.chunking(), "pump inspection", e0(), base).get(0).chunkId()).isEqualTo(keywordFavourite);
        // Keyword weight 0: the vector favourite wins.
        RetrievalParams vectorHeavy = new RetrievalParams(RetrievalParams.Mode.HYBRID, MODEL, 50, 50, 60, 1.0, 0.0, 50, 100, RetrievalParams.KeywordMatch.ANY);
        assertThat(retriever.search(c.ws(), c.chunking(), "pump inspection", e0(), vectorHeavy).get(0).chunkId()).isEqualTo(vectorFavourite);
    }

    @Test
    void vectorOnlyAndKeywordOnlyModes() {
        Corpus c = newCorpus();
        chunk(c, 0, "cats and dogs", e0());
        UUID hit = chunk(c, 1, "pump inspection", mix(0.5, 1));
        List<Candidate> vector = retriever.search(c.ws(), c.chunking(), "pump inspection", e0(), RetrievalParams.hybrid(MODEL).withMode(RetrievalParams.Mode.VECTOR));
        assertThat(vector).hasSize(2).allSatisfy(x -> {
            assertThat(x.vecRank()).isNotNull();
            assertThat(x.kwRank()).isNull();
        });
        List<Candidate> keyword = retriever.search(c.ws(), c.chunking(), "pump inspection", new float[DIM], RetrievalParams.hybrid(MODEL).withMode(RetrievalParams.Mode.KEYWORD));
        assertThat(keyword).singleElement().satisfies(x -> {
            assertThat(x.chunkId()).isEqualTo(hit);
            assertThat(x.vecRank()).isNull();
            assertThat(x.kwRank()).isEqualTo(1);
        });
    }

    @Test
    void naturalLanguageQuestionsOnlyMatchKeywordsWithAnyTermSemantics() {
        Corpus c = newCorpus();
        UUID hit = chunk(c, 0, "All pumps must be inspected every ninety days.", e0());
        RetrievalParams keywordOnly = RetrievalParams.hybrid(MODEL).withMode(RetrievalParams.Mode.KEYWORD);
        String question = "how often are pumps inspected";   // "often" appears nowhere in the chunk
        assertThat(retriever.search(c.ws(), c.chunking(), question, new float[DIM],
                keywordOnly.withKeywordMatch(RetrievalParams.KeywordMatch.ALL))).as("every word must match").isEmpty();
        assertThat(retriever.search(c.ws(), c.chunking(), question, new float[DIM],
                keywordOnly.withKeywordMatch(RetrievalParams.KeywordMatch.ANY))).extracting(Candidate::chunkId).containsExactly(hit);
    }

    @Test
    void anyTermQueryBuildingIsSafe() {
        assertThat(HybridRetriever.keywordQuery("how often are pumps inspected?", RetrievalParams.KeywordMatch.ANY))
                .isEqualTo("how or often or are or pumps or inspected");
        assertThat(HybridRetriever.keywordQuery("rock OR roll -ban \"quoted phrase\"", RetrievalParams.KeywordMatch.ANY))
                .isEqualTo("rock or roll or ban or quoted or phrase");
        assertThat(HybridRetriever.keywordQuery("???", RetrievalParams.KeywordMatch.ANY)).isEmpty();
        assertThat(HybridRetriever.keywordQuery("a b", RetrievalParams.KeywordMatch.ALL)).isEqualTo("a b");
    }

    @Test
    void supersededChunksAreNotKeywordSearchable() {
        Corpus c = newCorpus();
        UUID old = chunk(c, 0, "pump inspection every 90 days", e0());
        tx.workspace(c.ws(), null, j -> j.update("UPDATE chunks SET superseded = true WHERE id = ?", old));
        List<Candidate> keyword = retriever.search(c.ws(), c.chunking(), "pump inspection", new float[DIM],
                RetrievalParams.hybrid(MODEL).withMode(RetrievalParams.Mode.KEYWORD));
        assertThat(keyword).isEmpty();
    }

    // ------------------------------------------------------------ tenancy and the filtered-HNSW pitfall

    /**
     * On tiny test tables the planner prefers the exact btree path (embeddings_chunking_idx + sort) over HNSW, which
     * is fine in production but hides the behaviour under test. This drops that index for the duration of the check
     * (as the table owner) and always restores it. Tests in a class run sequentially, so nothing else observes the gap.
     */
    private <T> T withHnswOnly(java.util.function.Supplier<T> body) {
        return withoutIndexes(java.util.Map.of(
                "embeddings_chunking_idx", "CREATE INDEX embeddings_chunking_idx ON embeddings (workspace_id, chunking_id)",
                "embeddings_reuse_idx", "CREATE INDEX embeddings_reuse_idx ON embeddings (workspace_id, model, content_hash)"), body);
    }

    private <T> T withoutIndexes(java.util.Map<String, String> indexToDdl, java.util.function.Supplier<T> body) {
        try (java.sql.Connection owner = TestDb.ownerConnection(); java.sql.Statement st = owner.createStatement()) {
            for (String name : indexToDdl.keySet()) st.execute("DROP INDEX " + name);
            try {
                return body.get();
            } finally {
                for (String ddl : indexToDdl.values()) st.execute(ddl);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Candidate> rawVectorSearch(Corpus c, int k, int efSearch, String iterativeScan) {
        String sql = HybridSql.build(MODEL);
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("qvec", EmbeddingService.literal(e0())).addValue("qtext", "zzzz").addValue("chunking", c.chunking())
                .addValue("k_vec", k).addValue("k_kw", 0).addValue("k_fused", k).addValue("w_vec", 1.0).addValue("w_kw", 1.0).addValue("rrf_k", 60);
        return tx.workspace(c.ws(), null, j -> {
            j.queryForObject("SELECT set_config('enable_seqscan', 'off', true)", String.class); // force the HNSW index
            j.queryForObject("SELECT set_config('hnsw.ef_search', ?, true)", String.class, String.valueOf(efSearch));
            j.queryForObject("SELECT set_config('hnsw.iterative_scan', ?, true)", String.class, iterativeScan);
            return new NamedParameterJdbcTemplate(j).query(sql, args, HybridRetriever.MAPPER);
        });
    }

    @Test
    void filteredHnswSearchReturnsKRowsOnlyBecauseOfIterativeScan() {
        Corpus mine = newCorpus();
        Corpus noisy = newCorpus(); // another tenant whose vectors are all closer to the query than mine
        for (int i = 0; i < 30; i++) chunk(mine, i, "mine " + i, mix(0.5, 1 + i));
        for (int i = 0; i < 300; i++) chunk(noisy, i, "theirs " + i, mix(0.99, 1 + (i % 300)));

        // Without iterative scan the index returns its 40 nearest vectors, all of them the other tenant's,
        // which RLS then removes: the query comes back empty although 30 matching rows exist.
        List<Candidate> plain = withHnswOnly(() -> rawVectorSearch(mine, 20, 40, "off"));
        assertThat(plain).as("pitfall: post-filtered ANN search").hasSizeLessThan(20);
        // With iterative scan it keeps going until 20 rows survive the filter.
        List<Candidate> iterative = withHnswOnly(() -> rawVectorSearch(mine, 20, 40, "relaxed_order"));
        assertThat(iterative).hasSize(20);
        // The production retriever always enables it.
        assertThat(retriever.search(mine.ws(), mine.chunking(), "x", e0(), new RetrievalParams(RetrievalParams.Mode.VECTOR, MODEL, 20, 20, 60, 1, 1, 20, 40, RetrievalParams.KeywordMatch.ANY)))
                .hasSize(20).allSatisfy(x -> assertThat(x.text()).startsWith("mine "));
    }

    @Test
    void anotherTenantsChunkingYieldsNothing() {
        Corpus a = newCorpus();
        Corpus b = newCorpus();
        chunk(a, 0, "pump inspection", e0());
        assertThat(retriever.search(b.ws(), a.chunking(), "pump inspection", e0(), RetrievalParams.hybrid(MODEL))).isEmpty();
    }

    @Test
    void queryPlanUsesTheHnswIndexAndTheKeywordIndexIsUsable() {
        Corpus c = newCorpus();
        for (int i = 0; i < 20; i++) chunk(c, i, "pump inspection " + i, mix(0.5, 1 + i));
        String sql = "EXPLAIN " + HybridSql.build(MODEL);
        MapSqlParameterSource args = new MapSqlParameterSource()
                .addValue("qvec", EmbeddingService.literal(e0())).addValue("qtext", "pump inspection").addValue("chunking", c.chunking())
                .addValue("k_vec", 10).addValue("k_kw", 10).addValue("k_fused", 10).addValue("w_vec", 1.0).addValue("w_kw", 1.0).addValue("rrf_k", 60);
        List<String> plan = withHnswOnly(() -> tx.workspace(c.ws(), null, j -> {
            j.queryForObject("SELECT set_config('enable_seqscan', 'off', true)", String.class);
            return new NamedParameterJdbcTemplate(j).queryForList(sql, args, String.class);
        }));
        assertThat(String.join("\n", plan)).contains("embeddings_hnsw_bge_small");

        // Keyword side. Under RLS the planner cannot use the GIN index: the text-search match operator is not
        // leakproof, so Postgres refuses to evaluate it before the tenant policy. The search stays bounded to the
        // tenant's own chunks through the btree index instead (see docs/adr/0001-rls-and-full-text-search.md).
        List<String> underRls = tx.workspace(c.ws(), null, j -> {
            j.queryForObject("SELECT set_config('enable_seqscan', 'off', true)", String.class);
            return j.queryForList("EXPLAIN SELECT c.id FROM chunks c, websearch_to_tsquery('english', 'pump inspection') q WHERE c.tsv @@ q", String.class);
        });
        assertThat(String.join(" ", underRls)).contains("chunks_ws_chunking_idx").doesNotContain("chunks_tsv_gin");

        // The GIN index itself is valid and used as soon as RLS is out of the picture.
        List<String> withoutRls = new ArrayList<>();
        try (java.sql.Connection su = TestDb.superuserConnection(); java.sql.Statement st = su.createStatement()) {
            st.execute("SET enable_seqscan = off");
            try (java.sql.ResultSet rs = st.executeQuery("EXPLAIN SELECT c.id FROM chunks c, websearch_to_tsquery('english', 'pump inspection') q WHERE c.tsv @@ q")) {
                while (rs.next()) withoutRls.add(rs.getString(1));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(String.join(" ", withoutRls)).contains("chunks_tsv_gin");
    }

    @Test
    void unknownModelsAreRejectedBeforeAnySqlIsBuilt() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HybridSql.build("x'; DROP TABLE chunks; --"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
