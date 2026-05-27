package com.kalynx.centralindexer.it.db;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RepositoriesRepository} against a real PostgreSQL container.
 */
@RequiresDocker
class RepositoriesRepositoryIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private RepositoriesRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = buildPool(container);
        new DatabaseInitializer(pool).init();
        repo = new RepositoriesRepository(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    @Test
    void findAll_emptyTable_returnsEmptyList() throws Exception {
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void findAll_repositoryWithNoHead_returnsNullKalynxReviewHead() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");

        List<RepositoryRecord> results = repo.findAll();

        assertEquals(1, results.size());
        RepositoryRecord r = results.get(0);
        assertEquals("alice", r.owner());
        assertEquals("repo-a", r.repository());
        assertEquals("https://example.com/a", r.url());
        assertNull(r.kalynxReviewHead(), "kalynx_review_head should be null before first reconciliation");
    }

    @Test
    void updateKalynxReviewHead_validOwnerAndRepo_storesHead() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");

        repo.updateKalynxReviewHead("alice", "repo-a", "abc1234567890");

        String head = repo.findAll().get(0).kalynxReviewHead();
        assertEquals("abc1234567890", head);
    }

    @Test
    void updateKalynxReviewHead_existingHead_overwritesPreviousHead() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");
        repo.updateKalynxReviewHead("alice", "repo-a", "oldsha");

        repo.updateKalynxReviewHead("alice", "repo-a", "newsha");

        assertEquals("newsha", repo.findAll().get(0).kalynxReviewHead());
    }

    @Test
    void findAll_multipleRepositories_orderedByOwnerThenRepository() throws Exception {
        insertRepository("bob", "z-repo", "https://example.com/z");
        insertRepository("alice", "b-repo", "https://example.com/b");
        insertRepository("alice", "a-repo", "https://example.com/a");

        List<RepositoryRecord> results = repo.findAll();

        assertEquals(3, results.size());
        assertEquals("alice", results.get(0).owner());
        assertEquals("a-repo", results.get(0).repository());
        assertEquals("alice", results.get(1).owner());
        assertEquals("b-repo", results.get(1).repository());
        assertEquals("bob", results.get(2).owner());
        assertEquals("z-repo", results.get(2).repository());
    }

    @Test
    void findAll_mixedHeads_returnsNullAndNonNull() throws Exception {
        insertRepository("alice", "repo-a", "https://example.com/a");
        insertRepository("alice", "repo-b", "https://example.com/b");
        repo.updateKalynxReviewHead("alice", "repo-b", "sha123");

        List<RepositoryRecord> results = repo.findAll();

        assertEquals(2, results.size());
        assertNull(results.get(0).kalynxReviewHead());   // repo-a
        assertEquals("sha123", results.get(1).kalynxReviewHead()); // repo-b
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void insertRepository(String owner, String repository, String url) throws Exception {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO repositories (owner, repository, url) VALUES (?, ?, ?)")) {
            ps.setString(1, owner);
            ps.setString(2, repository);
            ps.setString(3, url);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    private ConnectionPool buildPool(PostgresTestContainer c) {
        DatabaseConfig config = GsonFactory.getInstance().fromJson("""
                {
                  "url": "%s",
                  "user": "%s",
                  "password": "%s",
                  "poolSize": 2
                }
                """.formatted(c.getJdbcUrl(), c.getUser(), c.getPassword()),
                DatabaseConfig.class);
        return new ConnectionPool(config);
    }
}
