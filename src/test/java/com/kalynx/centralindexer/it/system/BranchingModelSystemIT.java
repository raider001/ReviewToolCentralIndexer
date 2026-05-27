package com.kalynx.centralindexer.it.system;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.CommentsIndexRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end system tests for the branching model: branch schema, SSE event routing,
 * and the full client connect sequence ({@code GET /reviews} → {@code GET /branches}
 * → {@code GET /events/stream}).
 *
 * <p>Each test uses a real PostgreSQL container and starts the full {@link Application}
 * stack. Fixtures are in {@code src/test/resources/fixtures/branching/}.
 */
@RequiresDocker
class BranchingModelSystemIT {

    private static final String OWNER     = "sys-org";
    private static final String REPO      = "backend";
    private static final String FULL_REPO = OWNER + "/" + REPO;
    private static final String REPO_URL  = "https://github.com/" + FULL_REPO + ".git";
    private static final String BRANCH    = "feature/sys-test";
    private static final String HEAD      = "deadbeef01234567";
    private static final String REVIEW_ID = "sys-rev-001";

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

    // -----------------------------------------------------------------------
    // 1. Full client connect sequence
    // -----------------------------------------------------------------------

    @Test
    void fullConnectSequence_seedReviewAndBranches_returnsBothInResponse() throws Exception {
        // Field name must match RepoEntry.repositoryUrl (GSON uses Java field name)
        String reposJson = "[{\"owner\":\"" + OWNER + "\",\"repository\":\"" + REPO + "\"," +
                "\"branchName\":\"" + BRANCH + "\",\"headCommit\":\"" + HEAD + "\"," +
                "\"repositoryUrl\":\"" + REPO_URL + "\"}]";
        seedReview(REVIEW_ID, reposJson);
        seedRepository(OWNER, REPO, REPO_URL);
        seedBranch(OWNER, REPO, BRANCH, HEAD);
        seedReviewBranch(REVIEW_ID, OWNER, REPO, BRANCH);

        app = buildAndStartApp();
        int port = app.getPort();

        // Step 1: GET /reviews
        String reviewsBody = getJson(port, "/reviews");
        assertTrue(reviewsBody.contains(REVIEW_ID),
                "GET /reviews must return seeded review_id");
        assertTrue(reviewsBody.contains(REPO_URL),
                "GET /reviews must return repository_url from seeded repositories JSON");

        // Step 2: GET /branches?q=feature
        String branchesBody = getJson(port, "/branches?q=feature");
        assertTrue(branchesBody.contains(BRANCH),
                "GET /branches must return seeded branch name");

        // Step 3: SSE connect returns 200
        HttpURLConnection sseConn = openSse(port, FULL_REPO);
        assertEquals(200, sseConn.getResponseCode(), "SSE connect must return 200");
        sseConn.disconnect();
    }

    // -----------------------------------------------------------------------
    // 2. BRANCH_UPDATED delivers routing keys over SSE
    // -----------------------------------------------------------------------

    @Test
    void publish_branchUpdatedEvent_deliveredOverSseWithRoutingKeys() throws Exception {
        PublisherRegistry registry = new PublisherRegistry();
        app = buildAndStartApp(registry);

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpURLConnection sseConn = openSse(app.getPort(), FULL_REPO);
        drainAsync(sseConn, lines);
        Thread.sleep(200);

        registry.publish(new ReviewEvent(
                1L, Instant.now(), FULL_REPO,
                EventType.BRANCH_UPDATED, null, "actor", "delivery-bu-1",
                Map.of(
                        "repository_url", REPO_URL,
                        "branch_name",    BRANCH,
                        "head_commit",    HEAD)));

        String dataLine = pollDataLine(lines, 5);
        assertNotNull(dataLine, "SSE client must receive BRANCH_UPDATED within 5 s");
        assertTrue(dataLine.contains("repository_url"), "SSE frame must include repository_url key");
        assertTrue(dataLine.contains("branch_name"),    "SSE frame must include branch_name key");
        assertTrue(dataLine.contains("head_commit"),    "SSE frame must include head_commit key");
        assertTrue(dataLine.contains(REPO_URL),         "SSE frame must contain the URL value");
        assertTrue(dataLine.contains(HEAD),             "SSE frame must contain the commit SHA value");
        sseConn.disconnect();
    }

    // -----------------------------------------------------------------------
    // 3. REVIEW_CREATED delivers review_id over SSE
    // -----------------------------------------------------------------------

    @Test
    void publish_reviewCreatedEvent_deliveredOverSse() throws Exception {
        PublisherRegistry registry = new PublisherRegistry();
        app = buildAndStartApp(registry);

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpURLConnection sseConn = openSse(app.getPort(), FULL_REPO);
        drainAsync(sseConn, lines);
        Thread.sleep(200);

        registry.publish(new ReviewEvent(
                2L, Instant.now(), FULL_REPO,
                EventType.REVIEW_CREATED, REVIEW_ID, "actor", "delivery-rc-1",
                Map.of()));

        String dataLine = pollDataLine(lines, 5);
        assertNotNull(dataLine, "SSE client must receive REVIEW_CREATED within 5 s");
        assertTrue(dataLine.contains(REVIEW_ID), "SSE frame must contain the review_id value");
        sseConn.disconnect();
    }

    // -----------------------------------------------------------------------
    // 4. BRANCH_DELETED delivers branch_name over SSE
    // -----------------------------------------------------------------------

    @Test
    void publish_branchDeletedEvent_deliveredOverSse() throws Exception {
        PublisherRegistry registry = new PublisherRegistry();
        app = buildAndStartApp(registry);

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        HttpURLConnection sseConn = openSse(app.getPort(), FULL_REPO);
        drainAsync(sseConn, lines);
        Thread.sleep(200);

        registry.publish(new ReviewEvent(
                3L, Instant.now(), FULL_REPO,
                EventType.BRANCH_DELETED, null, "actor", "delivery-bd-1",
                Map.of(
                        "repository_url", REPO_URL,
                        "branch_name",    BRANCH)));

        String dataLine = pollDataLine(lines, 5);
        assertNotNull(dataLine, "SSE client must receive BRANCH_DELETED within 5 s");
        assertTrue(dataLine.contains("BRANCH_DELETED"), "SSE frame must identify event type");
        assertTrue(dataLine.contains(BRANCH),           "SSE frame must contain the branch_name value");
        sseConn.disconnect();
    }

    // -----------------------------------------------------------------------
    // 5. GET /branches prefix filtering
    // -----------------------------------------------------------------------

    @Test
    void getBranches_prefixFilter_returnsOnlyMatchingBranches() throws Exception {
        seedRepository(OWNER, REPO, REPO_URL);
        seedBranch(OWNER, REPO, "feature/alpha", "aaa111");
        seedBranch(OWNER, REPO, "feature/beta",  "bbb222");
        seedBranch(OWNER, REPO, "main",           "ccc333");

        app = buildAndStartApp();
        int port = app.getPort();

        String featureBranches = getJson(port, "/branches?q=feature/");
        assertTrue(featureBranches.contains("feature/alpha"),
                "prefix 'feature/' must match feature/alpha");
        assertTrue(featureBranches.contains("feature/beta"),
                "prefix 'feature/' must match feature/beta");

        String mainBranches = getJson(port, "/branches?q=mai");
        assertTrue(mainBranches.contains("main"),
                "prefix 'mai' must match main");
        assertTrue(!mainBranches.contains("feature/alpha"),
                "prefix 'mai' must not return feature branches");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private Application buildAndStartApp() throws Exception {
        return buildAndStartApp(new PublisherRegistry());
    }

    private Application buildAndStartApp(PublisherRegistry registry) throws Exception {
        DatabaseConfig dbConfig = buildDbConfig(container);
        AppConfig config = GsonFactory.getInstance().fromJson(
                buildConfigJson(0, dbConfig), AppConfig.class);

        ProviderPlugin noopPlugin = new ProviderPlugin() {
            @Override public String providerId() { return "branching-system-test"; }
            @Override public void start(ProviderConfig cfg, EventSink sink, WebhookRouter r) {}
            @Override public void reconcile(String repository, Instant since) {}
            @Override public void stop() {}
        };

        Application application = new Application(
                config, pool, noopPlugin, new WebhookRouterImpl(), registry,
                new MetricsCollector(pool),
                new RepositoriesRepository(pool), new BranchRepository(pool),
                new ReviewsIndexRepository(pool), new CommentsIndexRepository(pool));
        application.start();
        return application;
    }

    private HttpURLConnection openSse(int port, String repo) throws Exception {
        URL url = URI.create("http://localhost:" + port + "/events/stream?repository=" +
                repo.replace("/", "%2F")).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(15_000);
        conn.connect();
        return conn;
    }

    private void drainAsync(HttpURLConnection conn, BlockingQueue<String> queue) {
        Thread.ofVirtual().start(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isBlank()) queue.offer(line);
                }
            } catch (Exception ignored) {}
        });
    }

    private String pollDataLine(BlockingQueue<String> lines, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(500, TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith("data: ")) return line;
        }
        return null;
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

    private void seedReview(String reviewId, String reposJson) throws Exception {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO reviews_index (review_id, status, last_updated, repositories) " +
                "VALUES (?, 'open', now(), ?::jsonb) ON CONFLICT DO NOTHING")) {
            ps.setString(1, reviewId);
            ps.setString(2, reposJson != null ? reposJson : "[]");
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    private void seedRepository(String owner, String repo, String url) throws Exception {
        Connection conn = pool.acquire();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO repositories (owner, repository, url) VALUES (?, ?, ?) " +
                "ON CONFLICT DO NOTHING")) {
            ps.setString(1, owner);
            ps.setString(2, repo);
            ps.setString(3, url);
            ps.executeUpdate();
        } finally {
            pool.release(conn);
        }
    }

    private void seedBranch(String owner, String repo, String branch, String headCommit)
            throws Exception {
        Connection conn = pool.acquire();
        try {
            UUID repositoryId = lookupRepositoryId(conn, owner, repo);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO branches (repository_id, branch_name, head_commit) " +
                    "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                ps.setObject(1, repositoryId);
                ps.setString(2, branch);
                ps.setString(3, headCommit);
                ps.executeUpdate();
            }
        } finally {
            pool.release(conn);
        }
    }

    private void seedReviewBranch(String reviewId, String owner, String repo, String branch)
            throws Exception {
        Connection conn = pool.acquire();
        try {
            UUID repositoryId = lookupRepositoryId(conn, owner, repo);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO review_branches (review_id, repository_id, branch_name) " +
                    "VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, reviewId);
                ps.setObject(2, repositoryId);
                ps.setString(3, branch);
                ps.executeUpdate();
            }
        } finally {
            pool.release(conn);
        }
    }

    private UUID lookupRepositoryId(Connection conn, String owner, String repo) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT repository_id FROM repositories WHERE owner = ? AND repository = ?")) {
            ps.setString(1, owner);
            ps.setString(2, repo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    private String buildConfigJson(int port, DatabaseConfig db) {
        return String.format(
                "{\"server\":{\"port\":%d},"
                + "\"auth\":{\"enabled\":false},"
                + "\"database\":{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":5},"
                + "\"plugin\":{\"providerId\":\"branching-system-test\",\"properties\":{}}}",
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
