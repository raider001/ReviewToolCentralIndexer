package com.kalynx.centralindexer.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Queries the {@code branches} table for the {@code GET /branches} endpoint.
 *
 * <p>All queries order results by {@code (owner, repository, branch_name)} to match
 * the primary key order, which enables keyset pagination via the {@code cursor} parameter.
 */
public final class BranchRepository {

    private final ConnectionPool pool;

    /**
     * Constructs a {@code BranchRepository} backed by the given connection pool.
     *
     * @param pool the connection pool to use for queries
     */
    public BranchRepository(ConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Queries branches with optional prefix, repository, and cursor filters.
     *
     * <p>Conditions are combined with AND. Cursor uses a composite keyset comparison
     * ({@code (owner, repository, branch_name) > (?, ?, ?)}) which aligns with the
     * primary key index and is safe to combine with other WHERE conditions.
     *
     * @param prefix     optional branch name prefix; null or empty means no filter
     * @param owner      optional repository owner; null means no filter (paired with repository)
     * @param repository optional repository name; null means no filter (paired with owner)
     * @param limit      maximum number of results to return; must be &gt;= 1
     * @param cursor     optional keyset position as {@code [owner, repository, branchName]};
     *                   null means start from the beginning
     * @return ordered list of matching branch records
     * @throws SQLException         if the query fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public List<BranchRecord> query(String prefix, String owner, String repository,
                                    int limit, String[] cursor) throws SQLException, InterruptedException {
        StringBuilder sql = new StringBuilder(
                "SELECT owner, repository, branch_name FROM branches");
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();

        if (prefix != null && !prefix.isEmpty()) {
            conditions.add("branch_name LIKE ?");
            params.add(escapeLike(prefix) + "%");
        }
        if (owner != null) {
            conditions.add("owner = ?");
            params.add(owner);
            conditions.add("repository = ?");
            params.add(repository);
        }
        if (cursor != null) {
            conditions.add("(owner, repository, branch_name) > (?, ?, ?)");
            params.add(cursor[0]);
            params.add(cursor[1]);
            params.add(cursor[2]);
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY owner, repository, branch_name LIMIT ?");
        params.add(limit);

        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<BranchRecord> results = new ArrayList<>();
                while (rs.next()) {
                    results.add(new BranchRecord(
                            rs.getString("owner"),
                            rs.getString("repository"),
                            rs.getString("branch_name")));
                }
                return results;
            }
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Inserts or updates a branch row. On conflict the {@code head_commit} is updated.
     *
     * @param owner      the repository owner
     * @param repository the repository name
     * @param branchName the branch name
     * @param headCommit the current HEAD commit SHA
     * @throws SQLException         if the upsert fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public void upsert(String owner, String repository, String branchName, String headCommit)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO branches (owner, repository, branch_name, head_commit) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (owner, repository, branch_name) DO UPDATE SET head_commit = EXCLUDED.head_commit")) {
            ps.setString(1, owner);
            ps.setString(2, repository);
            ps.setString(3, branchName);
            ps.setString(4, headCommit);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Deletes a branch row. No-op if the row does not exist.
     *
     * @param owner      the repository owner
     * @param repository the repository name
     * @param branchName the branch name
     * @throws SQLException         if the delete fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public void delete(String owner, String repository, String branchName)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM branches WHERE owner = ? AND repository = ? AND branch_name = ?")) {
            ps.setString(1, owner);
            ps.setString(2, repository);
            ps.setString(3, branchName);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Returns the HEAD commit SHA for a specific branch, or empty if the branch is not tracked.
     *
     * @param owner      the repository owner
     * @param repository the repository name
     * @param branchName the branch name
     * @return the HEAD commit SHA wrapped in Optional, or empty if not found
     * @throws SQLException         if the query fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public Optional<String> findHeadCommit(String owner, String repository, String branchName)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT head_commit FROM branches WHERE owner = ? AND repository = ? AND branch_name = ?")) {
            ps.setString(1, owner);
            ps.setString(2, repository);
            ps.setString(3, branchName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString("head_commit")) : Optional.empty();
            }
        } finally {
            pool.release(conn);
        }
    }

    private static String escapeLike(String input) {
        return input.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
