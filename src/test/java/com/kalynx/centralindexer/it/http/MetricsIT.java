package com.kalynx.centralindexer.it.http;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.http.IndexerHttpServer;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for the {@code GET /metrics} endpoint executed over real TCP.
 *
 * <p>No Docker or PostgreSQL required — the connection pool is mocked.
 */
class MetricsIT {

    private IndexerHttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = startServer();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void getMetricsReturns200() throws Exception {
        HttpURLConnection conn = get("/metrics");
        assertEquals(200, conn.getResponseCode());
    }

    @Test
    void contentTypeIsJson() throws Exception {
        HttpURLConnection conn = get("/metrics");
        conn.getResponseCode();
        assertEquals("application/json", conn.getHeaderField("Content-Type"));
    }

    @Test
    void responseBodyContainsTopLevelKeys() throws Exception {
        HttpURLConnection conn = get("/metrics");
        String body = readBody(conn);

        assertTrue(body.contains("\"sse\""),       "must contain sse key");
        assertTrue(body.contains("\"db\""),        "must contain db key");
        assertTrue(body.contains("\"branches\""),  "must contain branches key");
        assertTrue(body.contains("\"system\""),    "must contain system key");
    }

    @Test
    void responseBodyContainsSseSubKeys() throws Exception {
        HttpURLConnection conn = get("/metrics");
        String body = readBody(conn);

        assertTrue(body.contains("\"connected_clients_total\""));
        assertTrue(body.contains("\"writers_per_second\""));
        assertTrue(body.contains("\"write_latency_p95_ms\""));
    }

    @Test
    void responseBodyContainsDbSubKeys() throws Exception {
        HttpURLConnection conn = get("/metrics");
        String body = readBody(conn);

        assertTrue(body.contains("\"get_reviews_p95_ms\""));
        assertTrue(body.contains("\"pool_active_connections\""));
        assertTrue(body.contains("\"pool_waiting_threads\""));
    }

    @Test
    void postReturns405() throws Exception {
        URL url = new URL("http://localhost:" + server.getPort() + "/metrics");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        conn.connect();
        assertEquals(405, conn.getResponseCode());
    }

    // --- helpers ---

    private IndexerHttpServer startServer() throws Exception {
        String json = "{\"server\":{\"port\":0},\"auth\":{\"enabled\":false}}";
        AppConfig config = GsonFactory.getInstance().fromJson(json, AppConfig.class);

        ConnectionPool pool = mock(ConnectionPool.class);
        Connection dbConn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(pool.acquire()).thenReturn(dbConn);
        when(dbConn.createStatement()).thenReturn(stmt);

        IndexerHttpServer srv = new IndexerHttpServer(config, pool, new WebhookRouterImpl(),
                new PublisherRegistry());
        srv.start();
        return srv;
    }

    private HttpURLConnection get(String path) throws Exception {
        URL url = new URL("http://localhost:" + server.getPort() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.connect();
        return conn;
    }

    private String readBody(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining());
        }
    }
}
