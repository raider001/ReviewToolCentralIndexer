package com.kalynx.centralindexer.it.db;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.CommentsIndexRepository;
import com.kalynx.centralindexer.db.CommentEntry;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link CommentsIndexRepository} against a real PostgreSQL container.
 */
@RequiresDocker
class CommentsIndexRepositoryIT {

    @Test
    void upsert_newComment_storedAndFoundByReviewId() throws Exception {
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            ConnectionPool pool = buildPool(container);
            new DatabaseInitializer(pool).init();

            RepositoriesRepository repos = new RepositoriesRepository(pool);
            ReviewsIndexRepository reviews = new ReviewsIndexRepository(pool);
            CommentsIndexRepository comments = new CommentsIndexRepository(pool);

            RepositoryRecord repo = repos.upsert("alice", "repo-a", "https://git.example.com/alice/repo-a.git");
            reviews.upsert("rev-1", "OPEN", Instant.now(), "[]");

            String commentId = "c0000000-0000-0000-0000-000000000001";
            Instant ts = Instant.parse("2026-05-26T10:00:00Z");
            comments.upsert(commentId, "rev-1", repo.repositoryId(), ts);

            List<CommentEntry> entries = comments.findByReviewId("rev-1");
            assertEquals(1, entries.size());
            assertEquals(commentId, entries.get(0).commentId());
            assertEquals("https://git.example.com/alice/repo-a.git", entries.get(0).repositoryUrl());
            assertEquals(ts, entries.get(0).lastUpdated());

            pool.close();
        }
    }

    @Test
    void upsert_existingComment_advancesLastUpdated() throws Exception {
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            ConnectionPool pool = buildPool(container);
            new DatabaseInitializer(pool).init();

            RepositoriesRepository repos = new RepositoriesRepository(pool);
            ReviewsIndexRepository reviews = new ReviewsIndexRepository(pool);
            CommentsIndexRepository comments = new CommentsIndexRepository(pool);

            RepositoryRecord repo = repos.upsert("alice", "repo-b", "https://git.example.com/alice/repo-b.git");
            reviews.upsert("rev-2", "OPEN", Instant.now(), "[]");

            String commentId = "c0000000-0000-0000-0000-000000000002";
            Instant t1 = Instant.parse("2026-05-26T10:00:00Z");
            Instant t2 = Instant.parse("2026-05-26T11:00:00Z");
            comments.upsert(commentId, "rev-2", repo.repositoryId(), t1);
            comments.upsert(commentId, "rev-2", repo.repositoryId(), t2);

            List<CommentEntry> entries = comments.findByReviewId("rev-2");
            assertEquals(1, entries.size());
            assertEquals(t2, entries.get(0).lastUpdated());

            pool.close();
        }
    }

    @Test
    void findByReviewId_multipleRepositories_returnsAllComments() throws Exception {
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            ConnectionPool pool = buildPool(container);
            new DatabaseInitializer(pool).init();

            RepositoriesRepository repos = new RepositoriesRepository(pool);
            ReviewsIndexRepository reviews = new ReviewsIndexRepository(pool);
            CommentsIndexRepository comments = new CommentsIndexRepository(pool);

            RepositoryRecord repoA = repos.upsert("alice", "repo-c", "https://git.example.com/alice/repo-c.git");
            RepositoryRecord repoB = repos.upsert("alice", "repo-d", "https://git.example.com/alice/repo-d.git");
            reviews.upsert("rev-3", "OPEN", Instant.now(), "[]");

            String commentId3 = "c0000000-0000-0000-0000-000000000003";
            String commentId4 = "c0000000-0000-0000-0000-000000000004";
            Instant t1 = Instant.parse("2026-05-26T09:00:00Z");
            Instant t2 = Instant.parse("2026-05-26T10:00:00Z");
            comments.upsert(commentId3, "rev-3", repoA.repositoryId(), t1);
            comments.upsert(commentId4, "rev-3", repoB.repositoryId(), t2);

            List<CommentEntry> entries = comments.findByReviewId("rev-3");
            assertEquals(2, entries.size());
            assertEquals(commentId3, entries.get(0).commentId());
            assertEquals(commentId4, entries.get(1).commentId());

            pool.close();
        }
    }

    @Test
    void findByReviewId_unknownReview_returnsEmptyList() throws Exception {
        try (PostgresTestContainer container = new PostgresTestContainer()) {
            ConnectionPool pool = buildPool(container);
            new DatabaseInitializer(pool).init();

            CommentsIndexRepository comments = new CommentsIndexRepository(pool);
            assertTrue(comments.findByReviewId("no-such-review").isEmpty());

            pool.close();
        }
    }

    private ConnectionPool buildPool(PostgresTestContainer container) {
        DatabaseConfig config = GsonFactory.getInstance().fromJson("""
                {
                  "url": "%s",
                  "user": "%s",
                  "password": "%s",
                  "poolSize": 2
                }
                """.formatted(container.getJdbcUrl(), container.getUser(), container.getPassword()),
                DatabaseConfig.class);
        return new ConnectionPool(config);
    }
}
