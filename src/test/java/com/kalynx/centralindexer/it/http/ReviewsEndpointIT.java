package com.kalynx.centralindexer.it.http;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.ReviewsIndexMapper;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.http.IndexerHttpServer;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the {@code GET /reviews} HTTP endpoint.
 *
 * <p>Exercises the full request path: HTTP over TCP → {@link com.kalynx.centralindexer.http.ReviewsHandler}
 * → {@link ReviewsIndexRepository} → PostgreSQL.
 */
@RequiresDocker
class ReviewsEndpointIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private ReviewsIndexRepository reviewsRepo;
    private IndexerHttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = buildPool(container);
        new DatabaseInitializer(pool).init();
        reviewsRepo = new ReviewsIndexRepository(pool);
        AppConfig config = GsonFactory.getInstance().fromJson(
                "{\"server\":{\"port\":0},\"auth\":{\"enabled\":false}}", AppConfig.class);
        server = new IndexerHttpServer(config, pool, new WebhookRouterImpl(),
                new PublisherRegistry(), new BranchRepository(pool), reviewsRepo);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        pool.close();
        container.close();
    }

    @Test
    void getReviews_emptyDatabase_returnsEmptyItemsArray() throws Exception {
        String body = get(null);
        assertTrue(body.contains("\"items\":[]"), "Empty DB must produce items:[]");
    }

    @Test
    void getReviews_validRequest_returns200WithJsonContentType() throws Exception {
        URL url = new URL("http://localhost:" + server.getPort() + "/reviews");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        assertEquals(200, conn.getResponseCode(), "GET /reviews must return 200");
        assertTrue(conn.getHeaderField("Content-Type").contains("application/json"),
                "Content-Type must be application/json");
    }

    @Test
    void getReviews_seededReview_returnedWithReviewId() throws Exception {
        reviewsRepo.upsert("rev-e2e-1", "OPEN", Instant.now(), "[]");
        String body = get(null);
        assertTrue(body.contains("\"rev-e2e-1\""), "Response must contain seeded review_id");
    }

    @Test
    void getReviews_repoUrlInRepositoriesJson_includedInResponse() throws Exception {
        String reposJson = ReviewsIndexMapper.toRepositoriesJson(List.of(
                new ReviewsIndexMapper.RepoEntry("alice", "repo",
                        "https://github.com/alice/repo", "main", "abc123")));
        reviewsRepo.upsert("rev-e2e-url", "OPEN", Instant.now(), reposJson);
        String body = get(null);
        assertTrue(body.contains("https://github.com/alice/repo"),
                "Response must include repository_url");
    }

    @Test
    void getReviews_branchInRepositoriesJson_extractedInResponse() throws Exception {
        String reposJson = ReviewsIndexMapper.toRepositoriesJson(List.of(
                new ReviewsIndexMapper.RepoEntry("alice", "repo",
                        "https://example.com", "feature/e2e-branch", "abc123")));
        reviewsRepo.upsert("rev-e2e-branch", "OPEN", Instant.now(), reposJson);
        String body = get(null);
        assertTrue(body.contains("feature/e2e-branch"),
                "review_branch must be extracted from repositories JSON");
    }

    @Test
    void getReviews_sinceFilter_excludesOlderReviews() throws Exception {
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        reviewsRepo.upsert("rev-old", "OPEN", base, "[]");
        reviewsRepo.upsert("rev-new", "OPEN", base.plusSeconds(100), "[]");

        String body = get("since=2026-01-01T00:00:50Z");
        assertTrue(body.contains("rev-new"), "Must include review updated after 'since'");
        assertFalse(body.contains("rev-old"), "Must exclude review updated before 'since'");
    }

    @Test
    void getReviews_statusFilter_returnsOnlyMatchingStatus() throws Exception {
        Instant now = Instant.now();
        reviewsRepo.upsert("rev-open", "OPEN", now, "[]");
        reviewsRepo.upsert("rev-approved", "APPROVED", now.plusSeconds(1), "[]");

        String body = get("status=OPEN");
        assertTrue(body.contains("rev-open"), "Must include OPEN review");
        assertFalse(body.contains("rev-approved"), "Must exclude non-OPEN review");
    }

    @Test
    void getReviews_sinceAndStatusFilter_returnsMatchingReviews() throws Exception {
        Instant base = Instant.parse("2026-03-01T00:00:00Z");
        reviewsRepo.upsert("rev-old-open", "OPEN", base, "[]");
        reviewsRepo.upsert("rev-new-open", "OPEN", base.plusSeconds(20), "[]");
        reviewsRepo.upsert("rev-new-approved", "APPROVED", base.plusSeconds(30), "[]");

        String body = get("since=2026-03-01T00:00:10Z&status=OPEN");
        assertTrue(body.contains("rev-new-open"), "Must include new OPEN review");
        assertFalse(body.contains("rev-old-open"), "Must exclude old OPEN review");
        assertFalse(body.contains("rev-new-approved"), "Must exclude APPROVED review");
    }

    @Test
    void postReviews_postMethod_returns405() throws Exception {
        URL url = new URL("http://localhost:" + server.getPort() + "/reviews");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        assertEquals(405, conn.getResponseCode(), "POST to /reviews must return 405");
    }

    @Test
    void getReviews_noFilter_returnsAllReviews() throws Exception {
        Instant now = Instant.now();
        reviewsRepo.upsert("rev-a", "OPEN", now, "[]");
        reviewsRepo.upsert("rev-b", "APPROVED", now.plusSeconds(1), "[]");
        reviewsRepo.upsert("rev-c", "CLOSED", now.plusSeconds(2), "[]");

        String body = get(null);
        assertTrue(body.contains("rev-a"), "Must contain rev-a");
        assertTrue(body.contains("rev-b"), "Must contain rev-b");
        assertTrue(body.contains("rev-c"), "Must contain rev-c");
    }

    private String get(String query) throws Exception {
        String path = "/reviews" + (query != null ? "?" + query : "");
        URL url = new URL("http://localhost:" + server.getPort() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return br.lines().collect(Collectors.joining());
        }
    }

    private ConnectionPool buildPool(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":5}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return new ConnectionPool(GsonFactory.getInstance().fromJson(json, DatabaseConfig.class));
    }
}