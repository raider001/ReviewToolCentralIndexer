package com.kalynx.centralindexer.it.db;

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

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link ReviewsIndexRepository#query(Instant, List)}.
 */
@RequiresDocker
class ReviewsIndexRepositoryQueryIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private ReviewsIndexRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = buildPool(container);
        new DatabaseInitializer(pool).init();
        repo = new ReviewsIndexRepository(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    @Test
    void query_noFilters_returnsAllReviews() throws Exception {
        Instant now = Instant.now();
        repo.upsert("rev-1", "OPEN", now, "[]");
        repo.upsert("rev-2", "APPROVED", now.plusSeconds(1), "[]");

        List<ReviewRecord> results = repo.query(null, null);

        assertEquals(2, results.size());
        List<String> ids = results.stream().map(ReviewRecord::reviewId).toList();
        assertTrue(ids.contains("rev-1"));
        assertTrue(ids.contains("rev-2"));
    }

    @Test
    void query_sinceFilter_returnsOnlyNewerReviews() throws Exception {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        repo.upsert("rev-old", "OPEN", base, "[]");
        repo.upsert("rev-new", "OPEN", base.plusSeconds(10), "[]");

        List<ReviewRecord> results = repo.query(base.plusSeconds(5), null);

        assertEquals(1, results.size());
        assertEquals("rev-new", results.get(0).reviewId());
    }

    @Test
    void query_statusFilter_returnsOnlyMatchingReviews() throws Exception {
        Instant now = Instant.now();
        repo.upsert("rev-open", "OPEN", now, "[]");
        repo.upsert("rev-approved", "APPROVED", now.plusSeconds(1), "[]");
        repo.upsert("rev-closed", "CLOSED", now.plusSeconds(2), "[]");

        List<ReviewRecord> results = repo.query(null, List.of("OPEN"));

        assertEquals(1, results.size());
        assertEquals("rev-open", results.get(0).reviewId());
    }

    @Test
    void query_multipleStatusValues_filtersCorrectly() throws Exception {
        Instant now = Instant.now();
        repo.upsert("rev-open", "OPEN", now, "[]");
        repo.upsert("rev-approved", "APPROVED", now.plusSeconds(1), "[]");
        repo.upsert("rev-closed", "CLOSED", now.plusSeconds(2), "[]");

        List<ReviewRecord> results = repo.query(null, List.of("OPEN", "APPROVED"));

        assertEquals(2, results.size());
        List<String> statuses = results.stream().map(ReviewRecord::status).toList();
        assertTrue(statuses.contains("OPEN"));
        assertTrue(statuses.contains("APPROVED"));
    }

    @Test
    void query_sinceAndStatusCombined_returnsMatchingReviews() throws Exception {
        Instant base = Instant.parse("2026-03-01T00:00:00Z");
        repo.upsert("rev-old-open", "OPEN", base, "[]");
        repo.upsert("rev-new-open", "OPEN", base.plusSeconds(20), "[]");
        repo.upsert("rev-new-approved", "APPROVED", base.plusSeconds(30), "[]");

        List<ReviewRecord> results = repo.query(base.plusSeconds(10), List.of("OPEN"));

        assertEquals(1, results.size());
        assertEquals("rev-new-open", results.get(0).reviewId());
    }

    @Test
    void query_noFilters_resultsOrderedByLastUpdatedDescending() throws Exception {
        Instant base = Instant.parse("2026-05-01T00:00:00Z");
        repo.upsert("rev-a", "OPEN", base, "[]");
        repo.upsert("rev-b", "OPEN", base.plusSeconds(5), "[]");
        repo.upsert("rev-c", "OPEN", base.plusSeconds(10), "[]");

        List<ReviewRecord> results = repo.query(null, null);

        assertEquals(3, results.size());
        assertEquals("rev-c", results.get(0).reviewId());
        assertEquals("rev-b", results.get(1).reviewId());
        assertEquals("rev-a", results.get(2).reviewId());
    }

    @Test
    void query_withRepositoriesJson_jsonPreservedInResult() throws Exception {
        String reposJson = "[{\"owner\":\"alice\",\"repository\":\"repo\","
                + "\"repositoryUrl\":\"https://example.com\","
                + "\"branchName\":\"main\",\"headCommit\":\"abc\"}]";
        repo.upsert("rev-1", "OPEN", Instant.now(), reposJson);

        List<ReviewRecord> results = repo.query(null, null);

        assertEquals(1, results.size());
        assertTrue(results.get(0).repositoriesJson().contains("alice"));
    }

    @Test
    void query_noMatchingStatus_returnsEmpty() throws Exception {
        repo.upsert("rev-1", "OPEN", Instant.now(), "[]");

        List<ReviewRecord> results = repo.query(null, List.of("NONEXISTENT_STATUS"));

        assertTrue(results.isEmpty());
    }

    private ConnectionPool buildPool(PostgresTestContainer c) {
        DatabaseConfig config = GsonFactory.getInstance().fromJson("""
                {
                  "url": "%s",
                  "user": "%s",
                  "password": "%s",
                  "poolSize": 3
                }
                """.formatted(c.getJdbcUrl(), c.getUser(), c.getPassword()),
                DatabaseConfig.class);
        return new ConnectionPool(config);
    }
}
