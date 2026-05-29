package com.kalynx.centralindexer.it.db;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.db.ReviewsIndexMapper;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that exercise the reviews_index upsert path against a real Postgres
 * instance started by {@link PostgresTestContainer}.
 */
@RequiresDocker
class ReviewsIndexIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        DatabaseConfig dbConfig = buildDbConfig(container);
        pool = new ConnectionPool(dbConfig);
        new DatabaseInitializer(pool).init();
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    @Test
    void upsert_withRepositoriesJson_storesJsonCorrectly() throws Exception {
        ReviewsIndexRepository repo = new ReviewsIndexRepository(pool);

        List<ReviewsIndexMapper.RepoEntry> entries = List.of(
                new ReviewsIndexMapper.RepoEntry("alice", "repo-a", "https://ex/a/repo-a", "main", "c0ffee"),
                new ReviewsIndexMapper.RepoEntry("bob", "repo-b", "https://ex/b/repo-b", "dev", "deadbeef")
        );

        String repositoriesJson = ReviewsIndexMapper.toRepositoriesJson(entries);
        repo.upsert("review-42", "OPEN", Instant.now(), repositoriesJson);

        List<String> rows = repo.queryAllAsJson();
        assertTrue(rows.stream().anyMatch(r ->
                r.contains("repo-a") &&
                r.contains("repo-b") &&
                r.contains("repositoryUrl") &&
                r.contains("https://ex/a/repo-a") &&
                r.contains("branchName") &&
                r.contains("headCommit") &&
                r.contains("c0ffee")),
                "Expect repositories JSON to contain complete structure with repositoryUrl, branchName, and headCommit");
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":5}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}

