package com.kalynx.centralindexer.it.http;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.http.IndexerHttpServer;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Load test verifying that {@code GET /branches} meets the M2 performance gate:
 * p95 latency {@code <} 200 ms for 50 concurrent typeahead requests against a
 * dataset of 1 000 branches (10 repositories × 100 branches each).
 */
@RequiresDocker
class BranchesLoadIT {

    private static final Logger log = LoggerFactory.getLogger(BranchesLoadIT.class);
    private static final int REPOS = 10;
    private static final int BRANCHES_PER_REPO = 100;
    private static final int CONCURRENT_CLIENTS = 50;
    private static final long P95_THRESHOLD_MS = 200;

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private IndexerHttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        DatabaseConfig dbConfig = buildDbConfig(container);
        pool = new ConnectionPool(dbConfig);
        new DatabaseInitializer(pool).init();
        seedData();

        AppConfig config = buildAppConfig(dbConfig);
        BranchRepository repo = new BranchRepository(pool);
        server = new IndexerHttpServer(config, pool, new WebhookRouterImpl(),
                new PublisherRegistry(), repo);
        server.start();
        log.info("Load test server started on port {}", server.getPort());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        pool.close();
        container.close();
    }

    @Test
    void p95LatencyUnder200msFor50ConcurrentTypeaheadRequests() throws Exception {
        String url = "http://localhost:" + server.getPort() + "/branches?q=branch-";
        CopyOnWriteArrayList<Long> latenciesMs = new CopyOnWriteArrayList<>();
        CopyOnWriteArrayList<Integer> statusCodes = new CopyOnWriteArrayList<>();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_CLIENTS);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CONCURRENT_CLIENTS; i++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        long start = System.nanoTime();
                        HttpURLConnection conn = (HttpURLConnection)
                                URI.create(url).toURL().openConnection();
                        conn.setConnectTimeout(5_000);
                        conn.setReadTimeout(5_000);
                        int status = conn.getResponseCode();
                        conn.disconnect();
                        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
                        latenciesMs.add(elapsedMs);
                        statusCodes.add(status);
                    } catch (Exception e) {
                        log.error("Load test client error", e);
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startGate.countDown();
            doneLatch.await();
        }

        assertEquals(CONCURRENT_CLIENTS, latenciesMs.size(),
                "All " + CONCURRENT_CLIENTS + " clients must complete");
        assertTrue(statusCodes.stream().allMatch(s -> s == 200),
                "All responses must be 200 OK");

        List<Long> sorted = new ArrayList<>(latenciesMs);
        Collections.sort(sorted);
        long p95 = sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
        log.info("GET /branches load test — p50={} ms  p95={} ms  p99={} ms",
                sorted.get(sorted.size() / 2),
                p95,
                sorted.get((int) Math.ceil(sorted.size() * 0.99) - 1));

        assertTrue(p95 < P95_THRESHOLD_MS,
                "p95 latency must be < " + P95_THRESHOLD_MS + " ms but was " + p95 + " ms");
    }

    private void seedData() throws Exception {
        Connection conn = pool.acquire();
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO repositories (owner, repository, url) VALUES (?, ?, ?)")) {
                for (int r = 0; r < REPOS; r++) {
                    ps.setString(1, "owner");
                    ps.setString(2, "repo-" + r);
                    ps.setString(3, "https://example.com/repo-" + r);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO branches (owner, repository, branch_name, head_commit) VALUES (?, ?, ?, ?)")) {
                for (int r = 0; r < REPOS; r++) {
                    for (int b = 0; b < BRANCHES_PER_REPO; b++) {
                        ps.setString(1, "owner");
                        ps.setString(2, "repo-" + r);
                        ps.setString(3, String.format("branch-%03d", b));
                        ps.setString(4, "sha" + r + b);
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
        } finally {
            pool.release(conn);
        }
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer c) {
        return GsonFactory.getInstance().fromJson("""
                {"url":"%s","user":"%s","password":"%s","poolSize":20}
                """.formatted(c.getJdbcUrl(), c.getUser(), c.getPassword()),
                DatabaseConfig.class);
    }

    private AppConfig buildAppConfig(DatabaseConfig dbConfig) {
        String json = String.format(
                "{\"server\":{\"port\":0},\"auth\":{\"enabled\":false},"
                + "\"database\":{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":20}}",
                dbConfig.getUrl(), dbConfig.getUser(), dbConfig.getPassword());
        return GsonFactory.getInstance().fromJson(json, AppConfig.class);
    }
}
