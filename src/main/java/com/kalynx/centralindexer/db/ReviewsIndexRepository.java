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

/**
 * Simple repository for the denormalised reviews_index read model.
 */
public final class ReviewsIndexRepository {

    private static final String SQL_UPSERT =
            "INSERT INTO reviews_index (review_id, status, last_updated, repositories) " +
            "VALUES (?, ?, ?, ?::jsonb) " +
            "ON CONFLICT (review_id) DO UPDATE SET " +
            "  status       = COALESCE(EXCLUDED.status, reviews_index.status), " +
            "  last_updated = GREATEST(reviews_index.last_updated, EXCLUDED.last_updated), " +
            "  repositories = (" +
            "    SELECT jsonb_agg(obj) FROM (" +
            "      SELECT DISTINCT ON (obj->>'owner', obj->>'repository') obj" +
            "      FROM (" +
            "        SELECT jsonb_array_elements(COALESCE(EXCLUDED.repositories,'[]'::jsonb)) obj" +
            "        UNION ALL" +
            "        SELECT jsonb_array_elements(COALESCE(reviews_index.repositories,'[]'::jsonb)) obj" +
            "      ) combined" +
            "      ORDER BY obj->>'owner', obj->>'repository'," +
            "               (obj->>'branchName' IS NULL)" +
            "    ) deduped" +
            "  )";

    private static final String SQL_QUERY_ALL =
            "SELECT review_id, status, last_updated, repositories FROM reviews_index ORDER BY last_updated DESC";

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

    public List<ReviewRecord> query(Instant since, List<String> statuses)
            throws SQLException, InterruptedException {
        boolean hasSince = since != null;
        boolean hasStatuses = statuses != null && !statuses.isEmpty();
        StringBuilder sql = new StringBuilder(
                "SELECT review_id, status, last_updated, repositories FROM reviews_index");
        if (hasSince || hasStatuses) {
            sql.append(" WHERE ");
            if (hasSince) {
                sql.append("last_updated > ?");
            }
            if (hasSince && hasStatuses) {
                sql.append(" AND ");
            }
            if (hasStatuses) {
                sql.append("status = ANY(?)");
            }
        }
        sql.append(" ORDER BY last_updated DESC");
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

