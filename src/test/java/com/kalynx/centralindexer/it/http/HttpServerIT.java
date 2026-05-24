package com.kalynx.centralindexer.it.http;
import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.http.IndexerHttpServer;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
/**
 * Integration tests for {@link IndexerHttpServer} executed over real TCP connections.
 *
 * <p>No Docker or PostgreSQL is required: the connection pool is mocked so that the
 * health endpoint can be fully exercised in-process.
 */
class HttpServerIT {
    private ConnectionPool pool;
    private Connection dbConn;
    private WebhookRouterImpl router;
    private IndexerHttpServer server;
    @BeforeEach
    void setUp() throws Exception {
        pool = mock(ConnectionPool.class);
        dbConn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(pool.acquire()).thenReturn(dbConn);
        when(dbConn.createStatement()).thenReturn(stmt);
        router = new WebhookRouterImpl();
    }
    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }
    @Test
    void healthEndpointReturns200() throws Exception {
        startServer(false, null);
        HttpURLConnection conn = openConnection("GET", "/health", null, null);
        assertEquals(200, conn.getResponseCode());
        String body = readBody(conn);
        assertTrue(body.contains("\"status\":\"UP\""));
        assertTrue(body.contains("\"db\":\"UP\""));
        assertEquals("application/json", conn.getHeaderField("Content-Type"));
    }
    @Test
    void webhookDispatchedToPlugin() throws Exception {
        AtomicBoolean handlerInvoked = new AtomicBoolean(false);
        router.registerPost("push", (headers, body) -> handlerInvoked.set(true));
        startServer(false, null);
        HttpURLConnection conn = openConnection("POST", "/webhooks/push", null,
                "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, conn.getResponseCode());
        assertTrue(handlerInvoked.get(), "Registered handler must be invoked");
    }
    @Test
    void authRejects401WithNoToken() throws Exception {
        startServer(true, "test-token");
        HttpURLConnection conn = openConnection("GET", "/events/stream?repository=test", null, null);
        assertEquals(401, conn.getResponseCode());
    }
    @Test
    void authRejects403WithWrongToken() throws Exception {
        startServer(true, "test-token");
        HttpURLConnection conn = openConnection("GET", "/events/stream?repository=test", "Bearer wrong-token", null);
        assertEquals(403, conn.getResponseCode());
    }
    @Test
    void authAcceptsCorrectToken() throws Exception {
        startServer(true, "test-token");
        HttpURLConnection conn = openConnection("GET", "/events/stream?repository=test", "Bearer test-token", null);
        int status = conn.getResponseCode();
        assertFalse(status == 401 || status == 403,
                "Correct token must not yield 401 or 403, got " + status);
    }
    @Test
    void authDisabledAllowsEventsWithoutToken() throws Exception {
        startServer(false, null);
        HttpURLConnection conn = openConnection("GET", "/events/stream?repository=test", null, null);
        int status = conn.getResponseCode();
        assertFalse(status == 401 || status == 403,
                "Auth disabled must not yield 401 or 403, got " + status);
    }
    private void startServer(boolean authEnabled, String token) throws Exception {
        AppConfig config = buildConfig(0, authEnabled, token);
        server = new IndexerHttpServer(config, pool, router, new PublisherRegistry());
        server.start();
    }
    private AppConfig buildConfig(int port, boolean authEnabled, String token) {
        String tokenJson = token == null ? "null" : "\"" + token + "\"";
        String json = String.format(
                "{\"server\":{\"port\":%d},\"auth\":{\"enabled\":%b,\"bearerToken\":%s}}",
                port, authEnabled, tokenJson);
        return GsonFactory.getInstance().fromJson(json, AppConfig.class);
    }
    private HttpURLConnection openConnection(String method, String path,
                                              String authHeader, byte[] body) throws Exception {
        URL url = new URL("http://localhost:" + server.getPort() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.getOutputStream().write(body);
        }
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