package com.kalynx.centralindexer.db;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.support.PostgresTestContainer;
import com.kalynx.centralindexer.support.RequiresDocker;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Integration tests for {@link DatabaseInitializer} against a real PostgreSQL container.
 */
@RequiresDocker
class DatabaseInitializerIT {

    @Test
    void createsTablesOnFreshDatabase() throws Exception {
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            ConnectionPool pool = buildPool(container);
            new DatabaseInitializer(pool).init();

            Connection conn = pool.acquire();
            assertTableExists(conn, "events");
            assertTableExists(conn, "repository_state");
            assertIndexExists(conn, "idx_events_repo_seq");
            assertIndexExists(conn, "idx_events_delivery_id");
            assertIndexExists(conn, "idx_events_timestamp");
            pool.release(conn);
            pool.close();
        }
    }

    @Test
    void idempotentOnExistingSchema() throws Exception {
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            ConnectionPool pool = buildPool(container);
            DatabaseInitializer initializer = new DatabaseInitializer(pool);
            initializer.init();
            assertDoesNotThrow(initializer::init);
            pool.close();
        }
    }

    private void assertTableExists(Connection conn, String tableName) throws Exception {
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = 'public' AND table_name = '" + tableName + "'");
        rs.next();
        assertEquals(1, rs.getInt(1), "Table '" + tableName + "' should exist");
    }

    private void assertIndexExists(Connection conn, String indexName) throws Exception {
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM pg_indexes " +
                "WHERE schemaname = 'public' AND indexname = '" + indexName + "'");
        rs.next();
        assertEquals(1, rs.getInt(1), "Index '" + indexName + "' should exist");
    }

    private ConnectionPool buildPool(PostgresTestContainer container) {
        DatabaseConfig config = GsonFactory.getInstance().fromJson("""
                {
                  "url": "%s",
                  "user": "%s",
                  "password": "%s",
                  "poolSize": 2
                }
                """.formatted(container.getJdbcUrl(), container.getUser(), container.getPassword()),
                DatabaseConfig.class);
        return new ConnectionPool(config);
    }
}

