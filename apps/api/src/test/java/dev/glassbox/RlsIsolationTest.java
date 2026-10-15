package dev.glassbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** SPEC 12: tenant isolation is enforced by the database and proven by tests. */
class RlsIsolationTest {
    static final UUID WS_A = UUID.randomUUID();
    static final UUID WS_B = UUID.randomUUID();
    static UUID userA;
    static UUID userB;

    /** Tables allowed to have no RLS. Everything else in public must be protected. */
    static final List<String> NO_RLS = List.of("flyway_schema_history");

    interface Work<T> { T run(Connection c) throws SQLException; }

    /** Runs work in one transaction with the RLS context set, like Tx does in the API. */
    static <T> T inContext(UUID ws, UUID user, Work<T> work) throws SQLException {
        try (Connection c = TestDb.appConnection()) {
            c.setAutoCommit(false);
            try {
                setContext(c, ws, user);
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        }
    }

    static long countIn(UUID ws, UUID user, String sql, Object... args) throws SQLException {
        return inContext(ws, user, c -> count(c, sql, args));
    }

    static void setContext(Connection c, UUID ws, UUID user) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.workspace_id', ?, true), set_config('app.user_id', ?, true)")) {
            ps.setString(1, ws == null ? "" : ws.toString());
            ps.setString(2, user == null ? "" : user.toString());
            ps.execute();
        }
    }

    static long count(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static UUID createUser(String email) throws SQLException {
        return inContext(null, null, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT auth_create_user(?, 'hash')")) {
                ps.setString(1, email);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getObject(1, UUID.class);
                }
            }
        });
    }

    static void seedWorkspace(UUID ws, UUID user, String canary) throws SQLException {
        inContext(ws, user, c -> {
            exec(c, "INSERT INTO workspaces (id, name) VALUES (?, ?)", ws, canary);
            exec(c, "INSERT INTO memberships (workspace_id, user_id, role) VALUES (?, ?, 'OWNER')", ws, user);
            exec(c, "INSERT INTO api_keys (workspace_id, key_hash, label) VALUES (?, ?, ?)", ws, "hash-" + canary, canary);
            exec(c, "INSERT INTO provider_keys (workspace_id, provider, encrypted_key) VALUES (?, 'openai', ?)", ws, canary.getBytes());
            exec(c, "INSERT INTO audit_log (workspace_id, actor, action) VALUES (?, ?, 'seed')", ws, canary);
            // P1 tables
            UUID doc = UUID.randomUUID(), ver = UUID.randomUUID(), chunking = UUID.randomUUID();
            exec(c, "INSERT INTO documents (id, workspace_id, title, source_type, content_hash, size_bytes) VALUES (?, ?, ?, 'MD', ?, 1)",
                    doc, ws, canary, "h-" + canary);
            exec(c, "INSERT INTO document_versions (id, workspace_id, document_id, version, content_hash, storage_path, normalized_text) "
                    + "VALUES (?, ?, ?, 1, 'h', 'p', ?)", ver, ws, doc, canary);
            exec(c, "INSERT INTO sections (workspace_id, document_id, document_version_id, ordinal, char_start, char_end, text) "
                    + "VALUES (?, ?, ?, 0, 0, 5, ?)", ws, doc, ver, canary);
            exec(c, "INSERT INTO chunkings (id, workspace_id, strategy, params) VALUES (?, ?, 'fixed', ?::jsonb)", chunking, ws, "{\"c\":\"" + canary + "\"}");
            UUID chunk = UUID.randomUUID();
            exec(c, "INSERT INTO chunks (id, workspace_id, document_id, document_version_id, chunking_id, ordinal, text, token_count, char_start, char_end) "
                    + "VALUES (?, ?, ?, ?, ?, 0, ?, 1, 0, 5)", chunk, ws, doc, ver, chunking, canary);
            exec(c, "INSERT INTO embeddings (chunk_id, workspace_id, model, dim, vector, content_hash, chunking_id) VALUES (?, ?, 'seed-model', 3, '[1,2,3]', ?, ?)",
                    chunk, ws, "hash-" + canary, chunking);
            exec(c, "INSERT INTO jobs (workspace_id, type, payload, idempotency_key) VALUES (?, 'T', ?::jsonb, ?)", ws, "{\"c\":\"" + canary + "\"}", "k-" + canary);
            exec(c, "INSERT INTO traces (workspace_id, kind, question, answer, data) VALUES (?, 'CHAT', ?, ?, ?::jsonb)", ws, canary, canary, "{\"c\":\"" + canary + "\"}");
            // P2 tables
            UUID set = UUID.randomUUID();
            exec(c, "INSERT INTO testsets (id, workspace_id, name, status, params) VALUES (?, ?, ?, 'DRAFT', ?::jsonb)", set, ws, canary, "{\"c\":\"" + canary + "\"}");
            UUID question = UUID.randomUUID();
            exec(c, "INSERT INTO test_questions (id, workspace_id, testset_id, question, expected_answer, qtype, origin) VALUES (?, ?, ?, ?, ?, 'FACTUAL', 'MANUAL')", question, ws, set, canary, canary);
            // P3 tables
            UUID cfg = UUID.randomUUID(), exp = UUID.randomUUID(), runA = UUID.randomUUID(), runB = UUID.randomUUID();
            exec(c, "INSERT INTO pipeline_configs (id, workspace_id, config, config_hash) VALUES (?, ?, ?::jsonb, ?)", cfg, ws, "{\"c\":\"" + canary + "\"}", "cfg-" + canary);
            exec(c, "INSERT INTO experiments (id, workspace_id, testset_id, name) VALUES (?, ?, ?, ?)", exp, ws, set, canary);
            exec(c, "INSERT INTO runs (id, workspace_id, experiment_id, config_id, label, ordinal) VALUES (?, ?, ?, ?, ?, 0)", runA, ws, exp, cfg, canary);
            exec(c, "INSERT INTO runs (id, workspace_id, experiment_id, config_id, label, ordinal) VALUES (?, ?, ?, ?, ?, 1)", runB, ws, exp, cfg, canary);
            UUID result = UUID.randomUUID();
            exec(c, "INSERT INTO run_results (id, workspace_id, run_id, question_id, answer) VALUES (?, ?, ?, ?, ?)", result, ws, runA, question, canary);
            exec(c, "INSERT INTO judge_labels (workspace_id, run_result_id, metric, item_key, judge_model, rubric_version, judge_label) VALUES (?, ?, 'correctness', 'answer', ?, 'v1', 'correct')", ws, result, canary);
            exec(c, "INSERT INTO judge_calibrations (workspace_id, judge_model, rubric_version, metric, n, agreement, kappa) VALUES (?, ?, 'v1', 'correctness', 30, 0.9, 0.8)", ws, canary);
            exec(c, "INSERT INTO run_metrics (run_id, workspace_id, metric, n) VALUES (?, ?, ?, 1)", runA, ws, "m-" + canary);
            exec(c, "INSERT INTO comparisons (workspace_id, run_a, run_b, metric, delta, verdict) VALUES (?, ?, ?, 'm', 0, ?)", ws, runA, runB, canary);
            // P4 tables
            exec(c, "INSERT INTO workspace_settings (workspace_id, default_pipeline) VALUES (?, ?::jsonb)", ws, "{\"c\":\"" + canary + "\"}");
            UUID tune = UUID.randomUUID();
            exec(c, "INSERT INTO autotunes (id, workspace_id, testset_id, name, objective) VALUES (?, ?, ?, ?, 'BEST_QUALITY')", tune, ws, set, canary);
            exec(c, "INSERT INTO autotune_candidates (workspace_id, autotune_id, stage, key, label, config) VALUES (?, ?, 'STAGE1', ?, ?, ?::jsonb)", ws, tune, "k-" + canary, canary, "{\"c\":\"" + canary + "\"}");
            return null;
        });
    }

    static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    @BeforeAll
    static void seed() throws SQLException {
        userA = createUser("a-" + WS_A + "@example.com");
        userB = createUser("b-" + WS_B + "@example.com");
        seedWorkspace(WS_A, userA, "CANARY-A");
        seedWorkspace(WS_B, userB, "CANARY-B");
    }

    /** Tables carrying a workspace_id column, plus workspaces whose id is the tenant key. */
    static List<String> tenantTables() throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Connection c = TestDb.ownerConnection();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT table_name FROM information_schema.columns "
                             + "WHERE table_schema = 'public' AND column_name = 'workspace_id' ORDER BY table_name")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        tables.add("workspaces");
        return tables;
    }

    static String tenantColumn(String table) {
        return table.equals("workspaces") ? "id" : "workspace_id";
    }

    @Test
    void everyTableHasForcedRlsAndAPolicy() throws SQLException {
        List<String> problems = new ArrayList<>();
        try (Connection c = TestDb.ownerConnection();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity, "
                             + "(SELECT count(*) FROM pg_policies p WHERE p.schemaname = 'public' AND p.tablename = c.relname) AS policies "
                             + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                             + "WHERE n.nspname = 'public' AND c.relkind = 'r'")) {
            while (rs.next()) {
                String name = rs.getString("relname");
                if (NO_RLS.contains(name)) continue;
                if (!rs.getBoolean("relrowsecurity")) problems.add(name + ": RLS not enabled");
                if (rs.getLong("policies") == 0) problems.add(name + ": no policy");
                // users is deliberately not FORCEd: SECURITY DEFINER auth functions run as the owner.
                boolean tenant = tenantTables().contains(name) || name.equals("refresh_tokens");
                if (tenant && !rs.getBoolean("relforcerowsecurity")) problems.add(name + ": RLS not forced");
            }
        }
        assertThat(problems).as("a new table without RLS fails the build").isEmpty();
    }

    @Test
    void appRoleCannotBypassRls() throws SQLException {
        try (Connection c = TestDb.appConnection()) {
            assertThat(count(c, "SELECT count(*) FROM pg_roles WHERE rolname = current_user AND (rolsuper OR rolbypassrls)")).isZero();
            assertThat(count(c, "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = current_user")).isZero();
        }
    }

    @Test
    void workspaceAseesNothingOfWorkspaceB() throws SQLException {
        for (String table : tenantTables()) {
            String col = tenantColumn(table);
            long ownRows = countIn(WS_A, userA, "SELECT count(*) FROM " + table + " WHERE " + col + " = ?", WS_A);
            long otherRows = countIn(WS_A, userA, "SELECT count(*) FROM " + table + " WHERE " + col + " = ?", WS_B);
            long visible = countIn(WS_A, userA, "SELECT count(*) FROM " + table);
            assertThat(ownRows).as("%s: A sees own rows", table).isPositive();
            assertThat(otherRows).as("%s: A sees B rows", table).isZero();
            assertThat(visible).as("%s: A sees only its own rows", table).isEqualTo(ownRows);
        }
    }

    @Test
    void noContextMeansNoRows() throws SQLException {
        for (String table : tenantTables()) {
            long visible = countIn(null, null, "SELECT count(*) FROM " + table);
            assertThat(visible).as("%s: fails closed without context", table).isZero();
        }
        // Even on a reused connection: after the transaction the GUC reads '' and must still fail closed.
        try (Connection c = TestDb.appConnection()) {
            c.setAutoCommit(false);
            setContext(c, WS_A, userA);
            c.commit();
            assertThat(count(c, "SELECT count(*) FROM api_keys")).isZero();
        }
    }

    @Test
    void cannotWriteIntoAnotherWorkspace() {
        assertThatThrownBy(() -> inContext(WS_A, userA, c -> {
            exec(c, "INSERT INTO api_keys (workspace_id, key_hash, label) VALUES (?, 'evil', 'evil')", WS_B);
            return null;
        })).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
    }

    @Test
    void cannotUpdateOrDeleteAnotherWorkspacesRows() throws SQLException {
        inContext(WS_A, userA, c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE api_keys SET label = 'pwned' WHERE workspace_id = ?")) {
                ps.setObject(1, WS_B);
                assertThat(ps.executeUpdate()).isZero();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM provider_keys WHERE workspace_id = ?")) {
                ps.setObject(1, WS_B);
                assertThat(ps.executeUpdate()).isZero();
            }
            return null;
        });
        assertThat(countIn(WS_B, userB, "SELECT count(*) FROM api_keys WHERE label = 'pwned'")).isZero();
    }

    @Test
    void usersAreVisibleOnlyToThemselvesAndFellowMembers() throws SQLException {
        assertThat(countIn(WS_A, userA, "SELECT count(*) FROM users WHERE id = ?", userA)).isEqualTo(1);
        assertThat(countIn(WS_A, userA, "SELECT count(*) FROM users WHERE id = ?", userB)).isZero();
        assertThat(countIn(null, null, "SELECT count(*) FROM users")).isZero();
        // Direct inserts are not allowed; registration goes through auth_create_user.
        assertThatThrownBy(() -> inContext(null, null, c -> {
            exec(c, "INSERT INTO users (email, password_hash) VALUES ('x@example.com', 'h')");
            return null;
        })).isInstanceOf(SQLException.class);
    }

    @Test
    void canaryNeverAppearsInWorkspaceAsData() throws SQLException {
        for (String table : tenantTables()) {
            long hits = countIn(WS_A, userA, "SELECT count(*) FROM " + table + " t WHERE t::text LIKE '%CANARY-B%'");
            assertThat(hits).as("%s leaks workspace B canary", table).isZero();
        }
    }

    static long countAsWorker(UUID ws, UUID user, String sql, Object... args) throws SQLException {
        return inContext(ws, user, c -> {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("SELECT set_config('app.worker', 'on', true)");
            }
            return count(c, sql, args);
        });
    }

    /** The queue's worker mode is the one deliberate cross-tenant read; it must not leak to ordinary requests. */
    @Test
    void jobsAreVisibleAcrossWorkspacesOnlyInWorkerMode() throws SQLException {
        assertThat(countIn(WS_A, userA, "SELECT count(*) FROM jobs WHERE workspace_id = ?", WS_B)).isZero();
        assertThat(countAsWorker(WS_A, userA, "SELECT count(*) FROM jobs WHERE workspace_id = ?", WS_B)).isEqualTo(1L);
        // The worker flag unlocks nothing but jobs.
        assertThat(countAsWorker(WS_A, userA, "SELECT count(*) FROM documents WHERE workspace_id = ?", WS_B)).isZero();
        assertThat(countAsWorker(WS_A, userA, "SELECT count(*) FROM chunks WHERE workspace_id = ?", WS_B)).isZero();
    }

    @Test
    void userCanListOwnWorkspacesButNotOthers() throws SQLException {
        assertThat(countIn(null, userA, "SELECT count(*) FROM workspaces")).isEqualTo(1);
        assertThat(countIn(null, userA, "SELECT count(*) FROM workspaces WHERE id = ?", WS_B)).isZero();
    }
}
