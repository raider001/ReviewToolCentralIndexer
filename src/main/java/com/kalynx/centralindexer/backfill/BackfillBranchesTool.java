package com.kalynx.centralindexer.backfill;

import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.ReviewsIndexMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads branch mappings from the normalised tables ({@code review_branches},
 * {@code branches}, {@code repositories}) and upserts the denormalised
 * {@code repositories} JSONB into {@code reviews_index}.
 *
 * <p>The tool is safe to run multiple times. Producing the same JSONB from the
 * same source data means repeated runs are idempotent — the {@code UPDATE}
 * writes the same value and the net result is unchanged.
 *
 * <p>Use {@link BackfillOptions#dryRun()} to report what would change without
 * modifying any data.
 */
public final class BackfillBranchesTool {

    private static final String SQL_ALL_REVIEW_IDS =
            "SELECT DISTINCT review_id FROM review_branches ORDER BY review_id";

    private static final String SQL_BRANCH_DATA_FOR_BATCH =
            "SELECT rb.review_id, rb.owner, rb.repository, rb.branch_name, " +
            "       b.head_commit, r.url " +
            "FROM review_branches rb " +
            "JOIN branches b " +
            "  ON rb.owner = b.owner AND rb.repository = b.repository " +
            "  AND rb.branch_name = b.branch_name " +
            "JOIN repositories r " +
            "  ON rb.owner = r.owner AND rb.repository = r.repository " +
            "WHERE rb.review_id = ANY(?) " +
            "ORDER BY rb.review_id, rb.owner, rb.repository, rb.branch_name";

    private static final String SQL_UPDATE_REPOSITORIES =
            "UPDATE reviews_index SET repositories = ?::jsonb WHERE review_id = ?";

    private final ConnectionPool pool;

    /**
     * Creates a tool backed by the given connection pool.
     *
     * @param pool the connection pool; must be connected to the target database
     */
    public BackfillBranchesTool(ConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * Runs the backfill according to the supplied options.
     *
     * @param options controls dry-run mode and batch size
     * @return a report summarising what was (or would be) changed
     * @throws SQLException         if any DB operation fails
     * @throws InterruptedException if the thread is interrupted while waiting for a connection
     */
    public BackfillReport run(BackfillOptions options) throws SQLException, InterruptedException {
        List<String> allReviewIds = fetchAllReviewIds();
        int totalReviews = allReviewIds.size();
        int updatedReviews = 0;
        int skipped = 0;

        Set<String> processedIds = new HashSet<>();

        for (int i = 0; i < allReviewIds.size(); i += options.batchSize()) {
            List<String> batch = allReviewIds.subList(
                    i, Math.min(i + options.batchSize(), allReviewIds.size()));

            Map<String, List<ReviewsIndexMapper.RepoEntry>> batchData = fetchBranchData(batch);

            for (String reviewId : batch) {
                List<ReviewsIndexMapper.RepoEntry> entries =
                        batchData.getOrDefault(reviewId, List.of());
                if (entries.isEmpty()) {
                    skipped++;
                    continue;
                }
                processedIds.add(reviewId);
                String json = ReviewsIndexMapper.toRepositoriesJson(entries);
                if (!options.dryRun()) {
                    updateRepositories(reviewId, json);
                }
                updatedReviews++;
            }
        }

        List<String> conflictIds = detectConflicts(allReviewIds, processedIds);
        return new BackfillReport(totalReviews, updatedReviews, skipped, conflictIds);
    }

    private List<String> fetchAllReviewIds() throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_ALL_REVIEW_IDS);
             ResultSet rs = ps.executeQuery()) {
            List<String> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString("review_id"));
            }
            return ids;
        } finally {
            pool.release(conn);
        }
    }

    private Map<String, List<ReviewsIndexMapper.RepoEntry>> fetchBranchData(List<String> reviewIds)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_BRANCH_DATA_FOR_BATCH)) {
            ps.setArray(1, conn.createArrayOf("text", reviewIds.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                Map<String, List<ReviewsIndexMapper.RepoEntry>> result = new LinkedHashMap<>();
                while (rs.next()) {
                    String reviewId = rs.getString("review_id");
                    result.computeIfAbsent(reviewId, k -> new ArrayList<>())
                          .add(new ReviewsIndexMapper.RepoEntry(
                                  rs.getString("owner"),
                                  rs.getString("repository"),
                                  rs.getString("url"),
                                  rs.getString("branch_name"),
                                  rs.getString("head_commit")));
                }
                return result;
            }
        } finally {
            pool.release(conn);
        }
    }

    private void updateRepositories(String reviewId, String json)
            throws SQLException, InterruptedException {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_REPOSITORIES)) {
            ps.setString(1, json);
            ps.setString(2, reviewId);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    private List<String> detectConflicts(List<String> allIds, Set<String> resolvedIds) {
        List<String> conflicts = new ArrayList<>();
        for (String id : allIds) {
            if (!resolvedIds.contains(id)) {
                conflicts.add(id);
            }
        }
        return conflicts;
    }
}
