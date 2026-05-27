package com.kalynx.centralindexer.it.system;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.http.IndexerHttpServer;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.CommentsIndexRepository;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * End-to-end system tests verifying the full event flow from webhook POST to SSE delivery
 * and health endpoint readiness probing.
 */
@RequiresDocker
class SystemIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private Application app;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        DatabaseConfig dbConfig = buildDbConfig(container);
        pool = new ConnectionPool(dbConfig);
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

    @Test
    void handle_webhookPost_eventDeliveredOverSse() throws Exception {
        String repo = "owner/system-full-flow";
        app = buildAndStartApp(repo);

        BlockingQueue<String> sseLines = new LinkedBlockingQueue<>();
        HttpURLConnection sseConn = openSseConnection(app.getPort(), repo);
        Thread.ofVirtual().start(() -> drainSseLines(sseConn, sseLines));
        Thread.sleep(300);

        postWebhook(app.getPort(), "push", "{}".getBytes(StandardCharsets.UTF_8));

        String dataLine = pollDataLine(sseLines, 5);
        assertNotNull(dataLine, "SSE client must receive an event within 5 seconds");
        assertTrue(dataLine.startsWith("data: "), "Received line must be a data frame");
        assertTrue(dataLine.contains(repo), "Data frame must contain the repository name");
    }

    @Test
    void health_dbBecomesAvailable_transitionsToUp() throws Exception {
        AtomicBoolean dbAvailable = new AtomicBoolean(false);
        ConnectionPool mockPool = mock(ConnectionPool.class);
        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        when(mockPool.acquire()).thenAnswer(inv -> {
            if (!dbAvailable.get()) {
                throw new SQLException("DB not yet available");
            }
            when(mockConn.createStatement()).thenReturn(mockStmt);
            return mockConn;
        });

        AppConfig config = GsonFactory.getInstance().fromJson(
                "{\"server\":{\"port\":0},\"auth\":{\"enabled\":false}}", AppConfig.class);
        IndexerHttpServer healthServer = new IndexerHttpServer(
                config, mockPool, new WebhookRouterImpl(), null);
        healthServer.start();

        try {
            String downBody = fetchHealth(healthServer.getPort());
            assertTrue(downBody.contains("\"db\":\"DOWN\""),
                    "Health must report db:DOWN when DB is unavailable");

            dbAvailable.set(true);

            long deadline = System.currentTimeMillis() + 5_000;
            String upBody = null;
            while (System.currentTimeMillis() < deadline) {
                String body = fetchHealth(healthServer.getPort());
                if (body.contains("\"db\":\"UP\"")) {
                    upBody = body;
                    break;
                }
                Thread.sleep(500);
            }
            assertNotNull(upBody,
                    "Health must transition to db:UP within 5 seconds of DB becoming available");
        } finally {
            healthServer.stop();
        }
    }

    private Application buildAndStartApp(String webhookTargetRepo) throws Exception {
        DatabaseConfig dbConfig = buildDbConfig(container);
        AppConfig config = GsonFactory.getInstance().fromJson(
                buildConfigJson(0, dbConfig), AppConfig.class);

        ProviderPlugin plugin = new ProviderPlugin() {
            @Override
            public String providerId() {
                return "system-test";
            }

            @Override
            public void start(ProviderConfig cfg, EventSink sink, WebhookRouter router) {
                router.registerPost("push", (headers, body) -> {
                    try {
                        sink.submit(new ReviewEvent(0L, Instant.now(), webhookTargetRepo,
                                EventType.REVIEW_CREATED, null, null,
                                "delivery-wh-" + System.nanoTime(), Map.of()));
                    } catch (RuntimeException ignored) {
                    }
                });
            }

            @Override
            public void reconcile(String repository, Instant since) {
            }

            @Override
            public void stop() {
            }
        };

        Application application = new Application(config, pool, plugin,
                new WebhookRouterImpl(), new PublisherRegistry(), new MetricsCollector(pool),
                new RepositoriesRepository(pool), new BranchRepository(pool),
                new ReviewsIndexRepository(pool), new CommentsIndexRepository(pool));
        application.start();
        return application;
    }

    private HttpURLConnection openSseConnection(int port, String repo) throws Exception {
        URL url = new URL("http://localhost:" + port
                + "/events/stream?repository=" + repo);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(15_000);
        conn.connect();
        return conn;
    }

    private void postWebhook(int port, String suffix, byte[] body) throws Exception {
        URL url = new URL("http://localhost:" + port + "/webhooks/" + suffix);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.getOutputStream().write(body);
        conn.getResponseCode();
        conn.disconnect();
    }

    private void drainSseLines(HttpURLConnection conn, BlockingQueue<String> queue) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) {
                    queue.offer(line);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private String pollDataLine(BlockingQueue<String> lines, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(500, TimeUnit.MILLISECONDS);
            if (line != null && line.startsWith("data: ")) {
                return line;
            }
        }
        return null;
    }

    private String fetchHealth(int port) throws Exception {
        URL url = new URL("http://localhost:" + port + "/health");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(3_000);
        conn.setReadTimeout(3_000);
        try {
            conn.connect();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                return br.lines().reduce("", String::concat);
            }
        } catch (java.net.SocketException e) {
            return "{\"db\":\"DOWN\"}";
        }
    }

    private String buildConfigJson(int port, DatabaseConfig db) {
        return String.format(
                "{\"server\":{\"port\":%d},"
                + "\"auth\":{\"enabled\":false},"
                + "\"database\":{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":5},"
                + "\"plugin\":{\"providerId\":\"system-test\",\"properties\":{}}}",
                port, db.getUrl(), db.getUser(), db.getPassword());
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":10}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}
