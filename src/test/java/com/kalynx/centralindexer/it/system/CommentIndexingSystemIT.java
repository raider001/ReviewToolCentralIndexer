package com.kalynx.centralindexer.it.system;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.CommentsIndexRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.spi.WebhookRouter;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.kalynx.centralindexer.startup.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for the comment-indexing pipeline: {@link CommentsIndexRepository},
 * {@code GET /reviews/{reviewId}/comments}, and the 404 smoke-probe case.
 */
@RequiresDocker
class CommentIndexingSystemIT {

    private static final String OWNER     = "comments-org";
    private static final String REPO      = "comments-repo";
    private static final String REPO_URL  = "https://git.example.com/" + OWNER + "/" + REPO + ".git";
    private static final String REVIEW_ID = "cmt-rev-001";

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private Application app;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = buildPool(container);
        new DatabaseInitializer(pool).init();
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
            app = null;
        }
        pool.close();
        container.close();
    }

    // ── 1. GET /reviews/{id}/comments returns indexed comments ───────────────

    @Test
    void getComments_indexedComment_appearsInHttpResponse() throws Exception {
        RepositoriesRepository repos = new RepositoriesRepository(pool);
        ReviewsIndexRepository reviews = new ReviewsIndexRepository(pool);
        CommentsIndexRepository comments = new CommentsIndexRepository(pool);

        String commentId = "c0000000-5151-0000-0000-000000000001";
        RepositoryRecord repo = repos.upsert(OWNER, REPO, REPO_URL);
        reviews.upsert(REVIEW_ID, "OPEN", Instant.now(), "[]");
        Instant ts = Instant.parse("2026-05-26T10:00:00Z");
        comments.upsert(commentId, REVIEW_ID, repo.repositoryId(), ts);

        app = buildAndStartApp();
        String body = getJson(app.getPort(), "/reviews/" + REVIEW_ID + "/comments");

        assertTrue(body.contains(commentId),
                "GET /reviews/{id}/comments must return indexed comment_id");
        assertTrue(body.contains(REPO_URL),
                "GET /reviews/{id}/comments must return repository_url");
    }

    // ── 2. Multiple comments from different repos all returned ───────────────

    @Test
    void getComments_multipleIndexedComments_allReturnedInResponse() throws Exception {
        RepositoriesRepository repos = new RepositoriesRepository(pool);
        ReviewsIndexRepository reviews = new ReviewsIndexRepository(pool);
        CommentsIndexRepository comments = new CommentsIndexRepository(pool);

        RepositoryRecord repoA = repos.upsert(OWNER, "repo-a", REPO_URL);
        RepositoryRecord repoB = repos.upsert(OWNER, "repo-b",
                "https://git.example.com/" + OWNER + "/repo-b.git");
        reviews.upsert(REVIEW_ID, "OPEN", Instant.now(), "[]");

        String commentId1 = "c0000000-5252-0000-0000-000000000001";
        String commentId2 = "c0000000-5252-0000-0000-000000000002";
        comments.upsert(commentId1, REVIEW_ID, repoA.repositoryId(),
                Instant.parse("2026-05-26T09:00:00Z"));
        comments.upsert(commentId2, REVIEW_ID, repoB.repositoryId(),
                Instant.parse("2026-05-26T10:00:00Z"));

        app = buildAndStartApp();
        String body = getJson(app.getPort(), "/reviews/" + REVIEW_ID + "/comments");

        assertTrue(body.contains(commentId1), "Response must contain first comment");
        assertTrue(body.contains(commentId2), "Response must contain second comment");
    }

    // ── 3. No comments returns 404 ───────────────────────────────────────────

    @Test
    void getComments_noCommentsIndexed_returns404() throws Exception {
        app = buildAndStartApp();
        int status = getStatus(app.getPort(), "/reviews/" + REVIEW_ID + "/comments");
        assertEquals(404, status, "GET /reviews/{id}/comments with no indexed comments must return 404");
    }

    // ── 4. Smoke probe: unknown review returns 404 ───────────────────────────

    @Test
    void getComments_unknownReview_returns404() throws Exception {
        app = buildAndStartApp();
        int status = getStatus(app.getPort(), "/reviews/smoke-probe/comments");
        assertEquals(404, status, "GET /reviews/smoke-probe/comments must return 404");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Application buildAndStartApp() throws Exception {
        DatabaseConfig dbConfig = buildDbConfig(container);
        AppConfig config = GsonFactory.getInstance().fromJson(
                buildConfigJson(0, dbConfig), AppConfig.class);

        ProviderPlugin noopPlugin = new ProviderPlugin() {
            @Override public String providerId() { return "comment-system-test"; }
            @Override public void start(ProviderConfig cfg, EventSink sink, WebhookRouter r) {}
            @Override public void reconcile(String repository, Instant since) {}
            @Override public void stop() {}
        };

        Application application = new Application(
                config, pool, noopPlugin, new WebhookRouterImpl(), new PublisherRegistry(),
                new MetricsCollector(pool),
                new RepositoriesRepository(pool), new BranchRepository(pool),
                new ReviewsIndexRepository(pool), new CommentsIndexRepository(pool));
        application.start();
        return application;
    }

    private String getJson(int port, String path) throws Exception {
        URL url = URI.create("http://localhost:" + port + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        conn.connect();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return br.lines().collect(Collectors.joining());
        } finally {
            conn.disconnect();
        }
    }

    private int getStatus(int port, String path) throws Exception {
        URL url = URI.create("http://localhost:" + port + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        conn.connect();
        int code = conn.getResponseCode();
        conn.disconnect();
        return code;
    }

    private String buildConfigJson(int port, DatabaseConfig db) {
        return String.format(
                "{\"server\":{\"port\":%d},"
                + "\"auth\":{\"enabled\":false},"
                + "\"database\":{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":5},"
                + "\"plugin\":{\"providerId\":\"comment-system-test\",\"properties\":{}}}",
                port, db.getUrl(), db.getUser(), db.getPassword());
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":10}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }

    private ConnectionPool buildPool(PostgresTestContainer c) {
        return new ConnectionPool(buildDbConfig(c));
    }
}
