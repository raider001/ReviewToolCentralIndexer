package com.kalynx.centralindexer.db;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.exception.DataSourceException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Manages a bounded pool of JDBC {@link Connection} objects.
 *
 * <p>Connections are created eagerly at construction time. If any connection cannot be
 * established the constructor throws {@link DataSourceException} immediately — there is
 * no retry logic. Recovery is delegated to the process supervisor.
 *
 * <p>{@link #acquire()} blocks until a connection becomes available.
 * {@link #release(Connection)} returns a connection to the pool for reuse.
 * {@link #close()} closes all connections and should be called once during shutdown.
 */
public final class ConnectionPool implements AutoCloseable {

    /**
     * Supplier of new JDBC connections. Package-private to allow substitution in tests.
     */
    @FunctionalInterface
    interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    private final LinkedBlockingQueue<Connection> pool;

    /**
     * Creates a {@code ConnectionPool} by opening {@code config.poolSize} JDBC connections.
     *
     * @param config the database configuration providing URL, credentials, and pool size
     * @throws DataSourceException if any JDBC connection cannot be established
     */
    public ConnectionPool(DatabaseConfig config) {
        this(config, () -> DriverManager.getConnection(
                config.getUrl(), config.getUser(), config.getPassword()));
    }

    ConnectionPool(DatabaseConfig config, ConnectionSupplier supplier) {
        pool = new LinkedBlockingQueue<>(config.getPoolSize());
        for (int i = 0; i < config.getPoolSize(); i++) {
            pool.offer(openConnection(supplier, config.getUrl()));
        }
    }

    /**
     * Acquires a connection from the pool, blocking until one is available.
     *
     * @return a JDBC connection
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public Connection acquire() throws InterruptedException {
        return pool.take();
    }

    /**
     * Returns a connection to the pool so it can be reused by other callers.
     *
     * @param connection the connection to return; must not be {@code null}
     */
    public void release(Connection connection) {
        pool.offer(connection);
    }

    /**
     * Closes all connections in the pool. Should be called exactly once during shutdown.
     */
    @Override
    public void close() {
        Connection conn;
        while ((conn = pool.poll()) != null) {
            closeQuietly(conn);
        }
    }

    private Connection openConnection(ConnectionSupplier supplier, String url) {
        try {
            return supplier.get();
        } catch (SQLException e) {
            throw new DataSourceException(
                    "Cannot connect to PostgreSQL at '" + url + "': " + e.getMessage(), e);
        }
    }

    private void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException ignored) {
        }
    }
}
