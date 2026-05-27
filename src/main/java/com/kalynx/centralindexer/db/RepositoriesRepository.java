package com.kalynx.centralindexer.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data-access object for the {@code repositories} table.
 *
 * <p>After M0, the table uses a surrogate {@code repository_id UUID} primary key.
 * {@code url} carries a {@code UNIQUE} constraint and is the conflict target for upserts.
 * {@code owner} and {@code repository} are retained as human-readable metadata columns.
 */
public final class RepositoriesRepository {

    private static final String SQL_FIND_ALL =
            "SELECT " + DbSchema.COL_REPOSITORY_ID + ", " + DbSchema.COL_OWNER + ", " +
            DbSchema.COL_REPOSITORY + ", " + DbSchema.COL_URL + ", " + DbSchema.COL_KALYNX_REVIEW_HEAD +
            " FROM " + DbSchema.TABLE_REPOSITORIES +
            " ORDER BY " + DbSchema.COL_OWNER + ", " + DbSchema.COL_REPOSITORY;

    private static final String SQL_FIND_BY_OWNER =
            "SELECT " + DbSchema.COL_REPOSITORY_ID + ", " + DbSchema.COL_OWNER + ", " +
            DbSchema.COL_REPOSITORY + ", " + DbSchema.COL_URL + ", " + DbSchema.COL_KALYNX_REVIEW_HEAD +
            " FROM " + DbSchema.TABLE_REPOSITORIES +
            " WHERE " + DbSchema.COL_OWNER + " = ? AND " + DbSchema.COL_REPOSITORY + " = ?";

    private static final String SQL_UPSERT =
            "INSERT INTO " + DbSchema.TABLE_REPOSITORIES +
            " (" + DbSchema.COL_OWNER + ", " + DbSchema.COL_REPOSITORY + ", " + DbSchema.COL_URL + ")" +
            " VALUES (?, ?, ?)" +
            " ON CONFLICT (" + DbSchema.COL_URL + ") DO UPDATE SET" +
            "  " + DbSchema.COL_OWNER + " = EXCLUDED." + DbSchema.COL_OWNER + "," +
            "  " + DbSchema.COL_REPOSITORY + " = EXCLUDED." + DbSchema.COL_REPOSITORY +
            " RETURNING " + DbSchema.COL_REPOSITORY_ID + ", " + DbSchema.COL_OWNER + ", " +
            DbSchema.COL_REPOSITORY + ", " + DbSchema.COL_URL + ", " + DbSchema.COL_KALYNX_REVIEW_HEAD;

    private static final String SQL_UPDATE_HEAD =
            "UPDATE " + DbSchema.TABLE_REPOSITORIES +
            " SET " + DbSchema.COL_KALYNX_REVIEW_HEAD + " = ?" +
            " WHERE " + DbSchema.COL_OWNER + " = ? AND " + DbSchema.COL_REPOSITORY + " = ?";

    private final ConnectionPool pool;

    public RepositoriesRepository(ConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Returns all rows from the {@code repositories} table, ordered by owner then repository name.
     *
     * @return an immutable snapshot of all tracked repositories
     * @throws SQLException         if the query fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public List<RepositoryRecord> findAll() throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try {
            try (PreparedStatement stmt = conn.prepareStatement(SQL_FIND_ALL)) {
                ResultSet rs = stmt.executeQuery();
                List<RepositoryRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(toRecord(rs));
                }
                return List.copyOf(result);
            }
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Returns the repository record for a specific owner and repository name, or empty if not found.
     *
     * @param owner      the repository owner
     * @param repository the repository name
     * @return an {@link Optional} containing the record, or empty if absent
     * @throws SQLException         if the query fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public Optional<RepositoryRecord> findByOwnerAndRepository(String owner, String repository)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement stmt = conn.prepareStatement(SQL_FIND_BY_OWNER)) {
            stmt.setString(1, owner);
            stmt.setString(2, repository);
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? Optional.of(toRecord(rs)) : Optional.empty();
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Inserts or updates a repository row keyed by {@code url}. On conflict the
     * {@code owner} and {@code repository} metadata columns are updated.
     *
     * <p>Returns the full {@link RepositoryRecord} so callers can immediately use the
     * stable {@code repository_id} for FK references in other tables.
     *
     * @param owner      the repository owner
     * @param repository the repository name
     * @param url        the canonical clone URL (conflict key)
     * @return the persisted record including the stable {@code repository_id}
     * @throws SQLException         if the upsert fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public RepositoryRecord upsert(String owner, String repository, String url)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try {
            try (PreparedStatement stmt = conn.prepareStatement(SQL_UPSERT)) {
                stmt.setString(1, owner);
                stmt.setString(2, repository);
                stmt.setString(3, url);
                ResultSet rs = stmt.executeQuery();
                if (!rs.next()) {
                    throw new SQLException("upsert returned no rows for url=" + url);
                }
                return toRecord(rs);
            }
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Advances the {@code kalynx_review_head} cursor for one repository.
     *
     * <p>Should be called only after all events in the reconciled range have been
     * successfully submitted, so that a crash between submission and this update causes
     * the next startup to re-reconcile the same range (idempotent).
     *
     * @param owner      the repository owner
     * @param repository the repository name
     * @param headCommit the new HEAD commit SHA to store
     * @throws SQLException         if the update fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public void updateKalynxReviewHead(String owner, String repository, String headCommit)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try {
            try (PreparedStatement stmt = conn.prepareStatement(SQL_UPDATE_HEAD)) {
                stmt.setString(1, headCommit);
                stmt.setString(2, owner);
                stmt.setString(3, repository);
                stmt.executeUpdate();
            }
        } finally {
            pool.release(conn);
        }
    }

    private static RepositoryRecord toRecord(ResultSet rs) throws SQLException {
        return new RepositoryRecord(
                rs.getString("repository_id"),
                rs.getString("owner"),
                rs.getString("repository"),
                rs.getString("url"),
                rs.getString("kalynx_review_head"));
    }
}
