package dev.glassbox;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One shared pgvector Postgres for all tests. Mirrors deploy/db/init/01-roles.sh: two roles, the vector
 * extension, and the database owned by glassbox_owner. Migrations run as the owner; tests then connect
 * as glassbox_app, exactly like production.
 */
public final class TestDb {
    public static final String OWNER_USER = "glassbox_owner";
    public static final String OWNER_PW = "owner-pw";
    public static final String APP_USER = "glassbox_app";
    public static final String APP_PW = "app-pw";

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    static {
        PG.start();
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + OWNER_USER + " LOGIN PASSWORD '" + OWNER_PW + "' NOSUPERUSER");
            s.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PW + "' NOSUPERUSER NOBYPASSRLS");
            s.execute("CREATE EXTENSION IF NOT EXISTS vector");
            s.execute("ALTER DATABASE " + PG.getDatabaseName() + " OWNER TO " + OWNER_USER);
            s.execute("ALTER SCHEMA public OWNER TO " + OWNER_USER);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        Flyway.configure().dataSource(PG.getJdbcUrl(), OWNER_USER, OWNER_PW)
                .locations("classpath:db/migrations").load().migrate();
    }

    private TestDb() {}

    public static String url() {
        return PG.getJdbcUrl();
    }

    public static Connection appConnection() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), APP_USER, APP_PW);
    }

    public static Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), OWNER_USER, OWNER_PW);
    }
}
