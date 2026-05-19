package com.kalynx.centralindexer.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Creates the database schema on first startup using {@code CREATE TABLE IF NOT EXISTS}
 * and {@code CREATE INDEX IF NOT EXISTS} statements.
 *
 * <p>Running against an already-initialised schema is a no-op — no migration framework
 * is required for v1.
 *
 * <h2>Schema</h2>
 * <pre>{@code
 * CREATE TABLE events (
 *     id            BIGSERIAL PRIMARY KEY,
 *     sequence_no   BIGINT        NOT NULL,
 *     repository    TEXT          NOT NULL,
 *     event_type    TEXT          NOT NULL,
 *     review_id     TEXT,
 *     actor_user    TEXT,
 *     payload       JSONB,
 *     timestamp     TIMESTAMPTZ   NOT NULL DEFAULT now(),
 *     delivery_id   TEXT
 * );
 *
 * CREATE TABLE repository_state (
 *     repository       TEXT        PRIMARY KEY,
 *     last_sequence_no BIGINT      NOT NULL DEFAULT 0,
 *     last_event_time  TIMESTAMPTZ NOT NULL DEFAULT now()
 * );
 * }</pre>
 */
public final class DatabaseInitializer {

    private final ConnectionPool pool;

    /**
     * Constructs a {@code DatabaseInitializer} that uses the supplied connection pool.
     *
     * @param pool the connection pool to use for DDL execution
     */
    public DatabaseInitializer(ConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Creates all required tables and indexes if they do not already exist.
     *
     * @throws SQLException         if any DDL statement fails
     * @throws InterruptedException if the thread is interrupted while waiting for a connection
     */
    public void init() throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try {
            executeDdl(conn);
        } finally {
            pool.release(conn);
        }
    }

    private void executeDdl(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createEventsTable());
            stmt.execute(createRepositoryStateTable());
            stmt.execute(createEventsRepoSeqIndex());
            stmt.execute(createEventsDeliveryIdIndex());
            stmt.execute(createEventsTimestampIndex());
        }
    }

    private String createEventsTable() {
        return "CREATE TABLE IF NOT EXISTS events (" +
               "    id            BIGSERIAL PRIMARY KEY," +
               "    sequence_no   BIGINT        NOT NULL," +
               "    repository    TEXT          NOT NULL," +
               "    event_type    TEXT          NOT NULL," +
               "    review_id     TEXT," +
               "    actor_user    TEXT," +
               "    payload       JSONB," +
               "    timestamp     TIMESTAMPTZ   NOT NULL DEFAULT now()," +
               "    delivery_id   TEXT" +
               ")";
    }

    private String createRepositoryStateTable() {
        return "CREATE TABLE IF NOT EXISTS repository_state (" +
               "    repository          TEXT        PRIMARY KEY," +
               "    last_sequence_no    BIGINT      NOT NULL DEFAULT 0," +
               "    last_event_time     TIMESTAMPTZ NOT NULL DEFAULT now()" +
               ")";
    }

    private String createEventsRepoSeqIndex() {
        return "CREATE UNIQUE INDEX IF NOT EXISTS idx_events_repo_seq " +
               "ON events (repository, sequence_no)";
    }

    private String createEventsDeliveryIdIndex() {
        return "CREATE UNIQUE INDEX IF NOT EXISTS idx_events_delivery_id " +
               "ON events (repository, delivery_id) WHERE delivery_id IS NOT NULL";
    }

    private String createEventsTimestampIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_events_timestamp ON events (timestamp)";
    }
}

