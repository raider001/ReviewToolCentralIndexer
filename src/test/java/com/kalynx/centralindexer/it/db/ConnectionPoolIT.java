package com.kalynx.centralindexer.it.db;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.exception.DataSourceException;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ConnectionPool} against a real PostgreSQL container.
 */
@RequiresDocker
class ConnectionPoolIT {

    @Test
    void acquireAndRelease() throws Exception {
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            ConnectionPool pool = new ConnectionPool(buildConfig(container, 2));
            Connection conn = pool.acquire();
            ResultSet rs = conn.createStatement().executeQuery("SELECT 1");
            rs.next();
            assertEquals(1, rs.getInt(1));
            pool.release(conn);
            pool.close();
        }
    }

    @Test
    void failsFastWhenDatabaseUnreachable() {
        long start = System.currentTimeMillis();
        assertThrows(DataSourceException.class, () -> {
            DatabaseConfig config = GsonFactory.getInstance().fromJson("""
                    {
                      "url": "jdbc:postgresql://localhost:19999/nonexistent",
                      "user": "nobody",
                      "password": "nothing",
                      "poolSize": 1
                    }
                    """, DatabaseConfig.class);
            new ConnectionPool(config);
        });
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 5000, "ConnectionPool should fail fast — took " + elapsed + " ms");
    }

    private DatabaseConfig buildConfig(PostgresTestContainer container, int poolSize) {
        String json = """
                {
                  "url": "%s",
                  "user": "%s",
                  "password": "%s",
                  "poolSize": %d
                }
                """.formatted(container.getJdbcUrl(), container.getUser(), container.getPassword(), poolSize);
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}
