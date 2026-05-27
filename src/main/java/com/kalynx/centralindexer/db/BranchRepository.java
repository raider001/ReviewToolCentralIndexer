package com.kalynx.centralindexer.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Queries and mutates the {@code branches} table for the {@code GET /branches} endpoint
 * and branch lifecycle events.
 *
 * <p>After M0, {@code branches} uses {@code (repository_id, branch_name)} as its primary
 * key. Write operations accept a {@code repository_id} UUID string (the surrogate key
 * returned by {@link RepositoriesRepository#upsert}).
 *
 * <p>The {@code query} method JOINs {@code repositories} to resolve {@code owner} and
 * {@code repository} for API responses and cursor encoding. Keyset pagination uses
 * {@code (owner, repository, branch_name)} for stable, human-readable ordering.
 */
public final class BranchRepository {

    private static final String SQL_QUERY_BASE =
            "SELECT b." + DbSchema.COL_REPOSITORY_ID + ", r." + DbSchema.COL_OWNER + ", r." +
            DbSchema.COL_REPOSITORY + ", b." + DbSchema.COL_BRANCH_NAME +
            " FROM " + DbSchema.TABLE_BRANCHES + " b" +
            " JOIN " + DbSchema.TABLE_REPOSITORIES + " r ON b." + DbSchema.COL_REPOSITORY_ID +
            " = r." + DbSchema.COL_REPOSITORY_ID;

    private static final String SQL_UPSERT =
            "INSERT INTO " + DbSchema.TABLE_BRANCHES +
            " (" + DbSchema.COL_REPOSITORY_ID + ", " + DbSchema.COL_BRANCH_NAME + ", " +
            DbSchema.COL_HEAD_COMMIT + ") VALUES (?::uuid, ?, ?)" +
            " ON CONFLICT (" + DbSchema.COL_REPOSITORY_ID + ", " + DbSchema.COL_BRANCH_NAME + ")" +
            " DO UPDATE SET " + DbSchema.COL_HEAD_COMMIT + " = EXCLUDED." + DbSchema.COL_HEAD_COMMIT;

    private static final String SQL_DELETE =
            "DELETE FROM " + DbSchema.TABLE_BRANCHES +
            " WHERE " + DbSchema.COL_REPOSITORY_ID + " = ?::uuid AND " +
            DbSchema.COL_BRANCH_NAME + " = ?";

    private static final String SQL_FIND_HEAD_COMMIT =
            "SELECT " + DbSchema.COL_HEAD_COMMIT +
            " FROM " + DbSchema.TABLE_BRANCHES +
            " WHERE " + DbSchema.COL_REPOSITORY_ID + " = ?::uuid AND " +
            DbSchema.COL_BRANCH_NAME + " = ?";

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
     * <p>Results are ordered by {@code (repository_id, branch_name)}. The cursor encodes
     * the last row's {@code repositoryId} and {@code branchName} as a two-part key for
     * keyset pagination.
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
        StringBuilder sql = new StringBuilder(SQL_QUERY_BASE);
        List<Object> params = new ArrayList<>();
        List<String> conditions = new ArrayList<>();

        if (prefix != null && !prefix.isEmpty()) {
            conditions.add("b.branch_name LIKE ?");
            params.add(escapeLike(prefix) + "%");
        }
        if (owner != null) {
            conditions.add("r.owner = ?");
            params.add(owner);
            conditions.add("r.repository = ?");
            params.add(repository);
        }
        if (cursor != null) {
            conditions.add("(r.owner, r.repository, b.branch_name) > (?, ?, ?)");
            params.add(cursor[0]);
            params.add(cursor[1]);
            params.add(cursor[2]);
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY r.owner, r.repository, b.branch_name LIMIT ?");
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
                            rs.getString("repository_id"),
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
     * @param repositoryId the surrogate UUID of the owning repository
     * @param branchName   the branch name
     * @param headCommit   the current HEAD commit SHA
     * @throws SQLException         if the upsert fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public void upsert(String repositoryId, String branchName, String headCommit)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
            ps.setString(1, repositoryId);
            ps.setString(2, branchName);
            ps.setString(3, headCommit);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Deletes a branch row. No-op if the row does not exist.
     *
     * @param repositoryId the surrogate UUID of the owning repository
     * @param branchName   the branch name
     * @throws SQLException         if the delete fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public void delete(String repositoryId, String branchName)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_DELETE)) {
            ps.setString(1, repositoryId);
            ps.setString(2, branchName);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Returns the HEAD commit SHA for a specific branch, or empty if the branch is not tracked.
     *
     * @param repositoryId the surrogate UUID of the owning repository
     * @param branchName   the branch name
     * @return the HEAD commit SHA wrapped in Optional, or empty if not found
     * @throws SQLException         if the query fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public Optional<String> findHeadCommit(String repositoryId, String branchName)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_HEAD_COMMIT)) {
            ps.setString(1, repositoryId);
            ps.setString(2, branchName);
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
