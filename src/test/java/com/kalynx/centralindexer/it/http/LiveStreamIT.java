package com.kalynx.centralindexer.it.http;

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
import com.kalynx.centralindexer.plugin.EventSinkImpl;
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
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end SSE streaming integration tests.
 *
 * <p>Confirms the complete chain:
 * {@code EventSinkImpl.submit()} -> {@link PublisherRegistry} ->
 * {@link com.kalynx.centralindexer.http.SseHandler} ->
 * SSE frame received by {@link HttpURLConnection} client.
 */
@RequiresDocker
class LiveStreamIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private EventSinkImpl sink;
    private PublisherRegistry registry;
    private IndexerHttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        DatabaseConfig dbConfig = buildDbConfig(container);
        pool = new ConnectionPool(dbConfig);
        new DatabaseInitializer(pool).init();
        registry = new PublisherRegistry();
        sink = new EventSinkImpl(registry);
        AppConfig config = GsonFactory.getInstance().fromJson(
                "{\"server\":{\"port\":0},\"auth\":{\"enabled\":false}}",
                AppConfig.class);
        server = new IndexerHttpServer(config, pool, new WebhookRouterImpl(), registry);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        pool.close();
        container.close();
    }

    @Test
    void submit_reviewCreatedEvent_reachesSSEClient() throws Exception {
        BlockingQueue<String> lines = connectSse("owner/live-test");
        Thread.sleep(300);
        sink.submit(testEvent("owner/live-test", "delivery-live-1"));
        String dataLine = pollDataLine(lines, 10);
        assertNotNull(dataLine, "SSE client must receive event within 10s");
        assertTrue(dataLine.startsWith("data: "), "Line must be a data frame");
    }

    @Test
    void submit_multipleClientsConnected_allReceiveEvent() throws Exception {
        BlockingQueue<String> client1 = connectSse("owner/multi-client");
        BlockingQueue<String> client2 = connectSse("owner/multi-client");
        BlockingQueue<String> client3 = connectSse("owner/multi-client");
        Thread.sleep(300);
        sink.submit(testEvent("owner/multi-client", "delivery-multi-1"));
        assertNotNull(pollDataLine(client1, 10), "Client 1 must receive event");
        assertNotNull(pollDataLine(client2, 10), "Client 2 must receive event");
        assertNotNull(pollDataLine(client3, 10), "Client 3 must receive event");
    }

    @Test
    void submit_branchUpdatedEvent_sseFrameContainsRoutingKeys() throws Exception {
        BlockingQueue<String> lines = connectSse("owner/routing-repo");
        Thread.sleep(300);
        sink.submit(new ReviewEvent(
                0L, Instant.now(), "owner/routing-repo",
                EventType.BRANCH_UPDATED, null, null, "delivery-routing-1",
                Map.of(
                        "repository_url", "https://github.com/owner/routing-repo.git",
                        "branch_name", "feature/test",
                        "head_commit", "abc123def456")));
        String dataLine = pollDataLine(lines, 10);
        assertNotNull(dataLine, "SSE client must receive BRANCH_UPDATED event");
        assertTrue(dataLine.contains("repository_url"), "SSE frame must include repository_url");
        assertTrue(dataLine.contains("head_commit"), "SSE frame must include head_commit");
        assertTrue(dataLine.contains("branch_name"), "SSE frame must include branch_name");
    }

    @Test
    void submit_eventForDifferentRepo_clientDoesNotReceive() throws Exception {
        BlockingQueue<String> clientA = connectSse("owner/repo-a");
        Thread.sleep(300);
        sink.submit(testEvent("owner/repo-b", "delivery-diff-1"));
        String line = clientA.poll(3, TimeUnit.SECONDS);
        if (line != null && !line.isEmpty() && !line.equals(":")) {
            throw new AssertionError(
                    "Client for owner/repo-a must not receive event for owner/repo-b but got: " + line);
        }
    }

    private BlockingQueue<String> connectSse(String repo) throws Exception {
        String url = "http://localhost:" + server.getPort()
                + "/events/stream?repository=" + repo;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        conn.connect();
        assertEquals(200, conn.getResponseCode(), "SSE connection must return 200");
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.offer(line);
                    }
                }
            } catch (Exception ignored) {
            }
        });
        return lines;
    }

    private String pollDataLine(BlockingQueue<String> lines, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(1, TimeUnit.SECONDS);
            if (line != null && line.startsWith("data: ")) {
                return line;
            }
        }
        return null;
    }

    private ReviewEvent testEvent(String repo, String deliveryId) {
        return new ReviewEvent(0L, Instant.now(), repo,
                EventType.REVIEW_CREATED, null, null, deliveryId, Map.of());
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer container) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":10}",
                container.getJdbcUrl(), container.getUser(), container.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}
