package com.kalynx.centralindexer.db;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.exception.DataSourceException;
import com.kalynx.centralindexer.json.GsonFactory;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ConnectionPool}.
 */
class ConnectionPoolTest {

    @Test
    void poolSizeMatchesConfig() throws Exception {
        Connection connection1 = mock(Connection.class);
        Connection connection2 = mock(Connection.class);
        DatabaseConfig config = buildConfig(2);

        int[] callCount = {0};
        Connection[] connections = {connection1, connection2};
        ConnectionPool pool = new ConnectionPool(config, () -> connections[callCount[0]++]);

        Connection c1 = pool.acquire();
        Connection c2 = pool.acquire();

        AtomicBoolean blocked = new AtomicBoolean(false);
        Thread blocker = Thread.ofVirtual().start(() -> {
            try {
                blocked.set(true);
                pool.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread.sleep(100);
        assertTrue(blocked.get(), "Third acquire should block when pool is exhausted");
        blocker.interrupt();
        pool.release(c1);
        pool.release(c2);
    }

    @Test
    void releaseReturnsConnectionToPool() throws Exception {
        Connection connection1 = mock(Connection.class);
        DatabaseConfig config = buildConfig(1);
        int[] callCount = {0};

        ConnectionPool pool = new ConnectionPool(config, () -> {
            callCount[0]++;
            return connection1;
        });

        Connection acquired = pool.acquire();
        pool.release(acquired);
        Connection reacquired = pool.acquire();

        assertTrue(callCount[0] == 1, "Supplier should only be called once — second acquire reuses released connection");
        assertTrue(acquired == reacquired, "Same connection instance should be returned after release");
        pool.release(reacquired);
    }

    @Test
    void throwsDataSourceExceptionWhenSupplierFails() {
        DatabaseConfig config = buildConfig(1);
        assertThrows(DataSourceException.class, () ->
                new ConnectionPool(config, () -> { throw new SQLException("Connection refused"); }));
    }

    private DatabaseConfig buildConfig(int poolSize) {
        String json = """
                {
                  "url": "jdbc:postgresql://localhost:5432/test",
                  "user": "test",
                  "password": "test",
                  "poolSize": %d
                }
                """.formatted(poolSize);
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}
