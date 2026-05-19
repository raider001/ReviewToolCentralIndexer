package com.kalynx.centralindexer.it.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.http.IndexerHttpServer;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration tests for the {@code GET /events} endpoint executed against a real PostgreSQL instance.
 */
@RequiresDocker
class EventsEndpointIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private EventRepository eventRepo;
    private IndexerHttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        DatabaseConfig dbConfig = buildDbConfig(container);
        pool = new ConnectionPool(dbConfig);
        new DatabaseInitializer(pool).init();
        eventRepo = new EventRepository(pool);
        AppConfig config = GsonFactory.getInstance().fromJson(
                "{\"server\":{\"port\":0},\"auth\":{\"enabled\":false}}",
                AppConfig.class);
        server = new IndexerHttpServer(config, pool, new WebhookRouterImpl(), eventRepo, null);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
        pool.close();
        container.close();
    }

    @Test
    void paginatesCorrectly() throws Exception {
        String repo = "owner/paginate-test";
        for (int i = 1; i <= 250; i++) {
            eventRepo.insert(new ReviewEvent(0L, Instant.now(), repo,
                    EventType.REVIEW_CREATED, null, null, "delivery-" + i, Map.of()));
        }

        JsonObject page1 = getEvents(repo, 0, 100);
        assertEquals(100, page1.getAsJsonArray("events").size(), "Page 1 must return 100 events");
        long nextSince1 = page1.get("nextSince").getAsLong();
        assertEquals(100L, nextSince1, "nextSince after page 1 must be 100");

        JsonObject page2 = getEvents(repo, nextSince1, 100);
        assertEquals(100, page2.getAsJsonArray("events").size(), "Page 2 must return 100 events");
        long nextSince2 = page2.get("nextSince").getAsLong();
        assertEquals(200L, nextSince2, "nextSince after page 2 must be 200");

        JsonObject page3 = getEvents(repo, nextSince2, 100);
        assertEquals(50, page3.getAsJsonArray("events").size(), "Page 3 must return the remaining 50 events");
        long nextSince3 = page3.get("nextSince").getAsLong();
        assertEquals(250L, nextSince3, "nextSince after page 3 must be 250");
    }

    @Test
    void returns410WhenCursorPruned() throws Exception {
        String repo = "owner/pruned-events-test";
        eventRepo.insert(new ReviewEvent(0L, Instant.now(), repo,
                EventType.REVIEW_CREATED, null, null, "delivery-pruned-1", Map.of()));
        pool.acquire().createStatement().executeUpdate(
                "UPDATE events SET timestamp = '2020-01-01T00:00:00Z' WHERE repository = '" + repo + "'");
        eventRepo.pruneOlderThan(1);

        HttpURLConnection conn = openConnection("GET",
                "/events?repository=" + repo + "&since=1", null);
        assertEquals(410, conn.getResponseCode(), "Pruned cursor must yield 410 Gone");
    }

    @Test
    void since0ReturnsAllRetainedEvents() throws Exception {
        String repo = "owner/since0-test";
        for (int i = 1; i <= 5; i++) {
            eventRepo.insert(new ReviewEvent(0L, Instant.now(), repo,
                    EventType.REVIEW_CREATED, null, null, "delivery-s" + i, Map.of()));
        }

        JsonObject response = getEvents(repo, 0, 100);
        assertEquals(5, response.getAsJsonArray("events").size(),
                "since=0 must return all 5 inserted events");
        assertEquals(5L, response.get("nextSince").getAsLong());
    }

    private JsonObject getEvents(String repo, long since, int limit) throws Exception {
        HttpURLConnection conn = openConnection("GET",
                "/events?repository=" + repo + "&since=" + since + "&limit=" + limit, null);
        assertEquals(200, conn.getResponseCode());
        String body = readBody(conn);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private HttpURLConnection openConnection(String method, String path, String authHeader) throws Exception {
        URL url = new URL("http://localhost:" + server.getPort() + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
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

    private DatabaseConfig buildDbConfig(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":10}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}

