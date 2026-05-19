package com.kalynx.centralindexer.it.http;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.http.IndexerHttpServer;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.it.support.TestKeystore;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests verifying that all endpoints remain reachable when the server
 * is configured for TLS (behaviour 9.5).
 *
 * <p>A self-signed test certificate is generated programmatically via {@link TestKeystore}
 * — no certificate files are checked into the repository.
 */
@RequiresDocker
class TlsIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private EventRepository eventRepo;
    private IndexerHttpServer server;
    private TestKeystore keystore;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        DatabaseConfig dbConfig = buildDbConfig(container);
        pool = new ConnectionPool(dbConfig);
        new DatabaseInitializer(pool).init();
        eventRepo = new EventRepository(pool);
        keystore = new TestKeystore();
        server = buildTlsServer(dbConfig);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        pool.close();
        keystore.close();
        container.close();
    }

    @Test
    void healthEndpointReachableOverHttps() throws Exception {
        HttpsURLConnection conn = openHttps("/health");
        conn.connect();
        assertEquals(200, conn.getResponseCode(),
                "GET /health over HTTPS must return 200");
        String body = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))
                .lines()
                .reduce("", String::concat);
        assertTrue(body.contains("\"status\":\"UP\""),
                "Health response body must contain status UP");
    }

    @Test
    void sseStreamReachableOverHttps() throws Exception {
        String repo = "owner/tls-sse-test";
        eventRepo.insert(new ReviewEvent(
                0L, Instant.now(), repo,
                EventType.REVIEW_CREATED, null, null,
                "delivery-tls-sse-1", Map.of()));

        HttpsURLConnection conn = openHttps(
                "/events/stream?repository=" + repo + "&since=0");
        conn.setReadTimeout(10_000);
        conn.connect();
        assertEquals(200, conn.getResponseCode(),
                "GET /events/stream over HTTPS must return 200");

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

        String dataLine = pollDataLine(lines, 8);
        assertTrue(dataLine != null && dataLine.startsWith("data: "),
                "SSE client must receive a replayed data frame over HTTPS");
    }

    private HttpsURLConnection openHttps(String path) throws Exception {
        URL url = URI.create("https://localhost:" + server.getPort() + path).toURL();
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(keystore.clientSslContext().getSocketFactory());
        conn.setHostnameVerifier((hostname, session) -> "localhost".equals(hostname));
        conn.setConnectTimeout(5_000);
        return conn;
    }

    private String pollDataLine(BlockingQueue<String> lines, int timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(1, TimeUnit.SECONDS);
            if (line != null && line.startsWith("data: ")) {
                return line;
            }
        }
        return null;
    }

    private IndexerHttpServer buildTlsServer(DatabaseConfig dbConfig) throws Exception {
        String keystorePathJson = GsonFactory.getInstance().toJson(keystore.getKeystorePath());
        String keystorePasswordJson = GsonFactory.getInstance().toJson(keystore.getPassword());
        String json = String.format(
                "{\"server\":{\"port\":0,\"tls\":{\"enabled\":true,\"keystorePath\":%s,"
                + "\"keystorePassword\":%s,\"keystoreType\":\"PKCS12\"}},"
                + "\"auth\":{\"enabled\":false},"
                + "\"database\":{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":5}}",
                keystorePathJson,
                keystorePasswordJson,
                dbConfig.getUrl(),
                dbConfig.getUser(),
                dbConfig.getPassword());
        AppConfig config = GsonFactory.getInstance().fromJson(json, AppConfig.class);
        return new IndexerHttpServer(config, pool, new WebhookRouterImpl(),
                eventRepo, new PublisherRegistry());
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer container) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":5}",
                container.getJdbcUrl(), container.getUser(), container.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}



