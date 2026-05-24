package com.kalynx.centralindexer.it.db;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
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
            assertTableExists(conn, "reviews_index");
            assertTableExists(conn, "repositories");
            assertTableExists(conn, "branches");
            assertTableExists(conn, "review_branches");
            assertIndexExists(conn, "idx_reviews_index_last_updated");
            assertIndexExists(conn, "idx_reviews_index_repositories_gin");
            assertIndexExists(conn, "idx_branches_name_prefix");
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

    @Test
    void repositoriesTableHasKalynxReviewHeadColumn() throws Exception {
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            ConnectionPool pool = buildPool(container);
            new DatabaseInitializer(pool).init();

            Connection conn = pool.acquire();
            assertColumnExists(conn, "repositories", "kalynx_review_head");
            pool.release(conn);
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

    private void assertColumnExists(Connection conn, String tableName, String columnName) throws Exception {
        ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM information_schema.columns " +
                "WHERE table_schema = 'public' AND table_name = '" + tableName +
                "' AND column_name = '" + columnName + "'");
        rs.next();
        assertEquals(1, rs.getInt(1), "Column '" + tableName + "." + columnName + "' should exist");
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
