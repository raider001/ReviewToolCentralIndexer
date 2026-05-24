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
 * CREATE TABLE reviews_index (
 *     review_id     TEXT        PRIMARY KEY,
 *     status        TEXT,
 *     last_updated  TIMESTAMPTZ,
 *     repositories  JSONB
 * );
 *
 * -- Branching model normalized tables (M1)
 * CREATE TABLE repositories (
 *     owner               TEXT NOT NULL,
 *     repository          TEXT NOT NULL,
 *     url                 TEXT NOT NULL,
 *     kalynx_review_head  TEXT,
 *     PRIMARY KEY (owner, repository)
 * );
 *
 * CREATE TABLE branches (
 *     owner       TEXT NOT NULL,
 *     repository  TEXT NOT NULL,
 *     branch_name TEXT NOT NULL,
 *     head_commit TEXT NOT NULL,
 *     PRIMARY KEY (owner, repository, branch_name),
 *     FOREIGN KEY (owner, repository) REFERENCES repositories
 * );
 *
 * CREATE TABLE review_branches (
 *     review_id   TEXT NOT NULL,
 *     owner       TEXT NOT NULL,
 *     repository  TEXT NOT NULL,
 *     branch_name TEXT NOT NULL,
 *     PRIMARY KEY (review_id, owner, repository, branch_name),
 *     FOREIGN KEY (review_id) REFERENCES reviews_index,
 *     FOREIGN KEY (owner, repository, branch_name) REFERENCES branches
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
            stmt.execute(createReviewsIndexTable());
            stmt.execute(createReviewsIndexLastUpdatedIndex());
            stmt.execute(createReviewsIndexRepositoriesGinIndex());
            stmt.execute(createRepositoriesTable());
            stmt.execute(addKalynxReviewHeadColumn());
            stmt.execute(createBranchesTable());
            stmt.execute(createReviewBranchesTable());
            stmt.execute(createBranchesNamePrefixIndex());
        }
    }

    private String createReviewsIndexTable() {
        return "CREATE TABLE IF NOT EXISTS reviews_index (" +
               "review_id TEXT PRIMARY KEY," +
               "status TEXT," +
               "last_updated TIMESTAMPTZ," +
               "repositories JSONB" +
               ")";
    }

    private String createReviewsIndexLastUpdatedIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_reviews_index_last_updated ON reviews_index (last_updated DESC)";
    }

    private String createReviewsIndexRepositoriesGinIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_reviews_index_repositories_gin ON reviews_index USING GIN (repositories)";
    }

    private String createRepositoriesTable() {
        return "CREATE TABLE IF NOT EXISTS repositories (" +
               "    owner               TEXT NOT NULL," +
               "    repository          TEXT NOT NULL," +
               "    url                 TEXT NOT NULL," +
               "    kalynx_review_head  TEXT," +
               "    PRIMARY KEY (owner, repository)" +
               ")";
    }

    private String addKalynxReviewHeadColumn() {
        return "ALTER TABLE repositories ADD COLUMN IF NOT EXISTS kalynx_review_head TEXT";
    }

    private String createBranchesTable() {
        return "CREATE TABLE IF NOT EXISTS branches (" +
               "    owner       TEXT NOT NULL," +
               "    repository  TEXT NOT NULL," +
               "    branch_name TEXT NOT NULL," +
               "    head_commit TEXT NOT NULL," +
               "    PRIMARY KEY (owner, repository, branch_name)," +
               "    FOREIGN KEY (owner, repository) REFERENCES repositories (owner, repository)" +
               ")";
    }

    private String createReviewBranchesTable() {
        return "CREATE TABLE IF NOT EXISTS review_branches (" +
               "    review_id   TEXT NOT NULL," +
               "    owner       TEXT NOT NULL," +
               "    repository  TEXT NOT NULL," +
               "    branch_name TEXT NOT NULL," +
               "    PRIMARY KEY (review_id, owner, repository, branch_name)," +
               "    FOREIGN KEY (review_id) REFERENCES reviews_index (review_id)," +
               "    FOREIGN KEY (owner, repository, branch_name) REFERENCES branches (owner, repository, branch_name)" +
               ")";
    }

    private String createBranchesNamePrefixIndex() {
        return "CREATE INDEX IF NOT EXISTS idx_branches_name_prefix " +
               "ON branches (branch_name text_pattern_ops)";
    }
}

