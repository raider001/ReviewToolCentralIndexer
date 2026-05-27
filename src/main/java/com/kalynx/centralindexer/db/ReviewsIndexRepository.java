package com.kalynx.centralindexer.db;

import com.google.gson.Gson;
import com.kalynx.centralindexer.json.GsonFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Simple repository for the denormalised reviews_index read model.
 */
public final class ReviewsIndexRepository {

    private static final String SQL_UPSERT =
            "INSERT INTO " + DbSchema.TABLE_REVIEWS_INDEX +
            " (" + DbSchema.COL_REVIEW_ID + ", " + DbSchema.COL_STATUS + ", " +
            DbSchema.COL_LAST_UPDATED + ", " + DbSchema.COL_REPOSITORIES + ")" +
            " VALUES (?, ?, ?, ?::jsonb)" +
            " ON CONFLICT (" + DbSchema.COL_REVIEW_ID + ") DO UPDATE SET" +
            "  " + DbSchema.COL_STATUS + "       = COALESCE(EXCLUDED." + DbSchema.COL_STATUS + ", " + DbSchema.TABLE_REVIEWS_INDEX + "." + DbSchema.COL_STATUS + ")," +
            "  " + DbSchema.COL_LAST_UPDATED + " = GREATEST(" + DbSchema.TABLE_REVIEWS_INDEX + "." + DbSchema.COL_LAST_UPDATED + ", EXCLUDED." + DbSchema.COL_LAST_UPDATED + ")," +
            "  " + DbSchema.COL_REPOSITORIES + " = (" +
            "    SELECT jsonb_agg(obj) FROM (" +
            "      SELECT DISTINCT ON (obj->>'owner', obj->>'repository') obj" +
            "      FROM (" +
            "        SELECT jsonb_array_elements(COALESCE(EXCLUDED." + DbSchema.COL_REPOSITORIES + ",'[]'::jsonb)) obj" +
            "        UNION ALL" +
            "        SELECT jsonb_array_elements(COALESCE(" + DbSchema.TABLE_REVIEWS_INDEX + "." + DbSchema.COL_REPOSITORIES + ",'[]'::jsonb)) obj" +
            "      ) combined" +
            "      ORDER BY obj->>'owner', obj->>'repository'," +
            "               (obj->>'branchName' IS NULL)" +
            "    ) deduped" +
            "  )";

    private static final String SQL_QUERY_ALL =
            "SELECT " + DbSchema.COL_REVIEW_ID + ", " + DbSchema.COL_STATUS + ", " +
            DbSchema.COL_LAST_UPDATED + ", " + DbSchema.COL_REPOSITORIES +
            " FROM " + DbSchema.TABLE_REVIEWS_INDEX +
            " ORDER BY " + DbSchema.COL_LAST_UPDATED + " DESC";

    private static final String SQL_FIND_STATUS_BY_ID =
            "SELECT " + DbSchema.COL_STATUS +
            " FROM " + DbSchema.TABLE_REVIEWS_INDEX +
            " WHERE " + DbSchema.COL_REVIEW_ID + " = ?";

    private static final String SQL_QUERY_FILTERED_BASE =
            "SELECT " + DbSchema.COL_REVIEW_ID + ", " + DbSchema.COL_STATUS + ", " +
            DbSchema.COL_LAST_UPDATED + ", " + DbSchema.COL_REPOSITORIES +
            " FROM " + DbSchema.TABLE_REVIEWS_INDEX;

    private final ConnectionPool pool;
    private final Gson gson;

    public ReviewsIndexRepository(ConnectionPool pool) {
        this.pool = pool;
        this.gson = GsonFactory.getInstance();
    }

    public void upsert(String reviewId, String status, Instant lastUpdated, String repositoriesJson)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
            ps.setString(1, reviewId);
            ps.setString(2, status);
            ps.setTimestamp(3, Timestamp.from(lastUpdated));
            ps.setString(4, repositoriesJson);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    public List<String> queryAllAsJson() throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_QUERY_ALL);
             ResultSet rs = ps.executeQuery()) {
            List<String> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(rs.getString("repositories"));
            }
            return rows;
        } finally {
            pool.release(conn);
        }
    }

    /**
     * Returns the status of a single review, or empty if the review is not known.
     *
     * @param reviewId the review to look up
     * @return the status string (e.g. {@code "OPEN"}, {@code "CLOSED"}) or empty
     * @throws SQLException         if the query fails
     * @throws InterruptedException if the thread is interrupted waiting for a connection
     */
    public Optional<String> findStatusById(String reviewId) throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_FIND_STATUS_BY_ID)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString("status")) : Optional.empty();
            }
        } finally {
            pool.release(conn);
        }
    }

    public List<ReviewRecord> query(Instant since, List<String> statuses)
            throws SQLException, InterruptedException {
        boolean hasSince = since != null;
        boolean hasStatuses = statuses != null && !statuses.isEmpty();
        StringBuilder sql = new StringBuilder(SQL_QUERY_FILTERED_BASE);
        if (hasSince || hasStatuses) {
            sql.append(" WHERE ");
            if (hasSince) {
                sql.append(DbSchema.COL_LAST_UPDATED).append(" > ?");
            }
            if (hasSince && hasStatuses) {
                sql.append(" AND ");
            }
            if (hasStatuses) {
                sql.append(DbSchema.COL_STATUS).append(" = ANY(?)");
            }
        }
        sql.append(" ORDER BY ").append(DbSchema.COL_LAST_UPDATED).append(" DESC");
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (hasSince) {
                ps.setTimestamp(idx++, Timestamp.from(since));
            }
            if (hasStatuses) {
                ps.setArray(idx, conn.createArrayOf("text", statuses.toArray()));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<ReviewRecord> results = new ArrayList<>();
                while (rs.next()) {
                    Timestamp ts = rs.getTimestamp("last_updated");
                    results.add(new ReviewRecord(
                            rs.getString("review_id"),
                            rs.getString("status"),
                            ts != null ? ts.toInstant() : null,
                            rs.getString("repositories")));
                }
                return results;
            }
        } finally {
            pool.release(conn);
        }
    }
}

