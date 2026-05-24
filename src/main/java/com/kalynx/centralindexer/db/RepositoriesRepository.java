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
 * <p>Provides reads of all tracked repositories (with their reconciliation cursors) and
 * targeted writes to advance the {@code kalynx_review_head} cursor after a successful
 * reconciliation pass.
 */
public final class RepositoriesRepository {

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
            try (PreparedStatement stmt = conn.prepareStatement(
                    "SELECT owner, repository, url, kalynx_review_head " +
                    "FROM repositories ORDER BY owner, repository")) {
                ResultSet rs = stmt.executeQuery();
                List<RepositoryRecord> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new RepositoryRecord(
                            rs.getString("owner"),
                            rs.getString("repository"),
                            rs.getString("url"),
                            rs.getString("kalynx_review_head")));
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
        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT owner, repository, url, kalynx_review_head " +
                "FROM repositories WHERE owner = ? AND repository = ?")) {
            stmt.setString(1, owner);
            stmt.setString(2, repository);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(new RepositoryRecord(
                        rs.getString("owner"),
                        rs.getString("repository"),
                        rs.getString("url"),
                        rs.getString("kalynx_review_head")));
            }
            return Optional.empty();
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Inserts or updates a repository row. On conflict (same owner + repository) the
     * {@code url} column is updated to the supplied value.
     *
     * @param owner      the repository owner
     * @param repository the repository name
     * @param url        the canonical clone URL
     * @throws SQLException         if the upsert fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public void upsert(String owner, String repository, String url)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try {
            try (PreparedStatement stmt = conn.prepareStatement(
                    "INSERT INTO repositories (owner, repository, url) VALUES (?, ?, ?) " +
                    "ON CONFLICT (owner, repository) DO UPDATE SET url = EXCLUDED.url")) {
                stmt.setString(1, owner);
                stmt.setString(2, repository);
                stmt.setString(3, url);
                stmt.executeUpdate();
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
            try (PreparedStatement stmt = conn.prepareStatement(
                    "UPDATE repositories SET kalynx_review_head = ? WHERE owner = ? AND repository = ?")) {
                stmt.setString(1, headCommit);
                stmt.setString(2, owner);
                stmt.setString(3, repository);
                stmt.executeUpdate();
            }
        } finally {
            pool.release(conn);
        }
    }
}
