package com.kalynx.centralindexer.it.backfill;

import com.kalynx.centralindexer.backfill.BackfillBranchesTool;
import com.kalynx.centralindexer.backfill.BackfillOptions;
import com.kalynx.centralindexer.backfill.BackfillReport;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.ReviewRecord;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link BackfillBranchesTool} against a real PostgreSQL container.
 *
 * <p>Each test seeds the normalised tables ({@code repositories}, {@code branches},
 * {@code review_branches}) and asserts the effect on {@code reviews_index.repositories}.
 */
@RequiresDocker
class BackfillBranchesIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private ReviewsIndexRepository reviewsRepo;
    private BackfillBranchesTool tool;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = buildPool(container);
        new DatabaseInitializer(pool).init();
        reviewsRepo = new ReviewsIndexRepository(pool);
        tool = new BackfillBranchesTool(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    // ---- dry-run: no DB writes -------------------------------------------

    @Test
    void dryRunMakesNoChangesToDatabase() throws Exception {
        seedReview("rev-1", null);
        seedRepository("alice", "repo", "https://github.com/alice/repo");
        seedBranch("alice", "repo", "main", "abc123");
        seedReviewBranch("rev-1", "alice", "repo", "main");

        tool.run(BackfillOptions.asDryRun());

        List<ReviewRecord> results = reviewsRepo.query(null, null);
        assertEquals(1, results.size());
        assertNull(results.get(0).repositoriesJson(),
                "Dry-run must not modify repositories JSON");
    }

    @Test
    void dryRunReportsTotalAndWouldUpdateCounts() throws Exception {
        seedReview("rev-a", null);
        seedReview("rev-b", null);
        seedRepository("alice", "repo", "https://github.com/alice/repo");
        seedBranch("alice", "repo", "main", "abc");
        seedReviewBranch("rev-a", "alice", "repo", "main");
        seedReviewBranch("rev-b", "alice", "repo", "main");

        BackfillReport report = tool.run(BackfillOptions.asDryRun());

        assertEquals(2, report.totalReviews(), "total must count distinct review IDs");
        assertEquals(2, report.updatedReviews(), "dry-run must count what would be updated");
        assertEquals(0, report.skipped());
        assertFalse(report.hasConflicts());
    }

    // ---- full run: populates repositories JSONB -------------------------

    @Test
    void fullRunPopulatesRepositoriesJson() throws Exception {
        seedReview("rev-1", null);
        seedRepository("alice", "repo", "https://github.com/alice/repo");
        seedBranch("alice", "repo", "main", "deadbeef");
        seedReviewBranch("rev-1", "alice", "repo", "main");

        BackfillReport report = tool.run(BackfillOptions.asFullRun());

        assertEquals(1, report.totalReviews());
        assertEquals(1, report.updatedReviews());

        List<ReviewRecord> results = reviewsRepo.query(null, null);
        String json = results.get(0).repositoriesJson();
        assertNotNull(json, "repositories JSON must be populated after full run");
        assertTrue(json.contains("alice"), "JSON must contain owner");
        assertTrue(json.contains("https://github.com/alice/repo"), "JSON must contain repository_url");
        assertTrue(json.contains("main"), "JSON must contain branch name");
        assertTrue(json.contains("deadbeef"), "JSON must contain head commit");
    }

    @Test
    void fullRunWithMultipleBranchesPerReview() throws Exception {
        seedReview("rev-multi", null);
        seedRepository("alice", "repo", "https://github.com/alice/repo");
        seedBranch("alice", "repo", "main", "aaa");
        seedBranch("alice", "repo", "develop", "bbb");
        seedReviewBranch("rev-multi", "alice", "repo", "main");
        seedReviewBranch("rev-multi", "alice", "repo", "develop");

        tool.run(BackfillOptions.asFullRun());

        List<ReviewRecord> results = reviewsRepo.query(null, null);
        String json = results.get(0).repositoriesJson();
        assertTrue(json.contains("main"), "JSON must contain main branch");
        assertTrue(json.contains("develop"), "JSON must contain develop branch");
        assertTrue(json.contains("aaa"), "JSON must contain main head commit");
        assertTrue(json.contains("bbb"), "JSON must contain develop head commit");
    }

    @Test
    void fullRunWithMultipleRepositoriesPerReview() throws Exception {
        seedReview("rev-repos", null);
        seedRepository("alice", "repo-a", "https://github.com/alice/repo-a");
        seedRepository("alice", "repo-b", "https://github.com/alice/repo-b");
        seedBranch("alice", "repo-a", "main", "c0ffee");
        seedBranch("alice", "repo-b", "feature", "facade");
        seedReviewBranch("rev-repos", "alice", "repo-a", "main");
        seedReviewBranch("rev-repos", "alice", "repo-b", "feature");

        tool.run(BackfillOptions.asFullRun());

        List<ReviewRecord> results = reviewsRepo.query(null, null);
        String json = results.get(0).repositoriesJson();
        assertTrue(json.contains("repo-a"), "JSON must contain repo-a");
        assertTrue(json.contains("repo-b"), "JSON must contain repo-b");
    }

    @Test
    void fullRunPreservesStatusAndLastUpdated() throws Exception {
        Instant ts = Instant.parse("2026-01-15T12:00:00Z");
        seedReviewWithStatus("rev-status", "APPROVED", ts);
        seedRepository("alice", "repo", "https://example.com");
        seedBranch("alice", "repo", "main", "abc");
        seedReviewBranch("rev-status", "alice", "repo", "main");

        tool.run(BackfillOptions.asFullRun());

        List<ReviewRecord> results = reviewsRepo.query(null, null);
        assertEquals(1, results.size());
        assertEquals("APPROVED", results.get(0).status(),
                "Backfill must not change status");
        assertEquals(ts.getEpochSecond(),
                results.get(0).lastUpdated().getEpochSecond(),
                "Backfill must not change last_updated");
    }

    // ---- idempotency --------------------------------------------------------

    @Test
    void secondRunProducesSameResult() throws Exception {
        seedReview("rev-idem", null);
        seedRepository("alice", "repo", "https://github.com/alice/repo");
        seedBranch("alice", "repo", "main", "abc123");
        seedReviewBranch("rev-idem", "alice", "repo", "main");

        BackfillReport first = tool.run(BackfillOptions.asFullRun());
        BackfillReport second = tool.run(BackfillOptions.asFullRun());

        assertEquals(first.totalReviews(), second.totalReviews());
        // Both runs should process (and write) the same row — second is idempotent
        assertEquals(1, second.updatedReviews());

        // DB state should be identical after either run
        List<ReviewRecord> results = reviewsRepo.query(null, null);
        assertTrue(results.get(0).repositoriesJson().contains("abc123"),
                "Repositories JSON must remain consistent after idempotent re-run");
    }

    // ---- batching -----------------------------------------------------------

    @Test
    void smallBatchSizeProcessesAllReviews() throws Exception {
        seedRepository("alice", "repo", "https://example.com");
        seedBranch("alice", "repo", "main", "abc");
        for (int i = 1; i <= 5; i++) {
            String reviewId = "rev-batch-" + i;
            seedReview(reviewId, null);
            seedReviewBranch(reviewId, "alice", "repo", "main");
        }

        BackfillReport report = tool.run(BackfillOptions.asFullRunBatch(2));

        assertEquals(5, report.totalReviews(), "All 5 reviews must be processed");
        assertEquals(5, report.updatedReviews());
    }

    // ---- empty state --------------------------------------------------------

    @Test
    void emptyTablesProducesEmptyReport() throws Exception {
        BackfillReport report = tool.run(BackfillOptions.asFullRun());
        assertEquals(0, report.totalReviews());
        assertEquals(0, report.updatedReviews());
        assertEquals(0, report.skipped());
        assertFalse(report.hasConflicts());
    }

    @Test
    void reviewWithNoBranchesIsSkipped() throws Exception {
        // A review exists in reviews_index but has no review_branches rows
        reviewsRepo.upsert("rev-no-branches", "OPEN", Instant.now(), null);

        BackfillReport report = tool.run(BackfillOptions.asFullRun());

        assertEquals(0, report.totalReviews(), "review with no branch mappings is not in review_branches");
        assertEquals(0, report.updatedReviews());
    }

    // ---- helpers ------------------------------------------------------------

    private void seedReview(String reviewId, String reposJson) throws Exception {
        reviewsRepo.upsert(reviewId, "OPEN", Instant.now(), reposJson);
    }

    private void seedReviewWithStatus(String reviewId, String status, Instant ts) throws Exception {
        reviewsRepo.upsert(reviewId, status, ts, null);
    }

    private void seedRepository(String owner, String repository, String url) throws Exception {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO repositories (owner, repository, url) VALUES (?, ?, ?) " +
                "ON CONFLICT DO NOTHING")) {
            ps.setString(1, owner);
            ps.setString(2, repository);
            ps.setString(3, url);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    private void seedBranch(String owner, String repository, String branchName, String headCommit)
            throws Exception {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO branches (owner, repository, branch_name, head_commit) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, owner);
            ps.setString(2, repository);
            ps.setString(3, branchName);
            ps.setString(4, headCommit);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    private void seedReviewBranch(String reviewId, String owner, String repository,
                                   String branchName) throws Exception {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO review_branches (review_id, owner, repository, branch_name) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, reviewId);
            ps.setString(2, owner);
            ps.setString(3, repository);
            ps.setString(4, branchName);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    private ConnectionPool buildPool(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":5}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return new ConnectionPool(GsonFactory.getInstance().fromJson(json, DatabaseConfig.class));
    }
}
