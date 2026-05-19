package com.kalynx.centralindexer.it.startup;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.kalynx.centralindexer.startup.Application;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Integration test confirming that the HTTP server does not accept connections until
 * reconciliation has completed.
 */
@RequiresDocker
class StartupOrderingIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private EventRepository eventRepo;
    private Application app;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        DatabaseConfig dbConfig = buildDbConfig(container);
        pool = new ConnectionPool(dbConfig);
        new DatabaseInitializer(pool).init();
        eventRepo = new EventRepository(pool);
    }

    @AfterEach
    void tearDown() {
        if (app != null) {
            app.stop();
        }
        pool.close();
        container.close();
    }

    @Test
    void httpServerNotOpenBeforeReconcileCompletes() throws Exception {
        int port = findFreePort();
        insertRepositoryState("owner/ordering-test");

        ProviderPlugin plugin = mock(ProviderPlugin.class);
        doAnswer(inv -> {
            Thread.sleep(2_000);
            return null;
        }).when(plugin).reconcile(anyString(), any());

        DatabaseConfig dbConfig = buildDbConfig(container);
        AppConfig config = GsonFactory.getInstance().fromJson(
                buildAppConfigJson(port, dbConfig), AppConfig.class);

        app = new Application(config, pool, eventRepo, plugin, new WebhookRouterImpl(), new PublisherRegistry());

        Thread appThread = Thread.ofVirtual().start(() -> {
            try {
                app.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(500);
        assertNotListening(port);

        appThread.join(10_000);

        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + "/health").openConnection();
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(5_000);
        conn.connect();
        assertEquals(200, conn.getResponseCode(),
                "Health endpoint must return 200 once the HTTP server starts after reconciliation");
    }

    private void assertNotListening(int port) {
        try {
            new Socket("localhost", port).close();
            fail("Expected ConnectException — HTTP server must not be listening on port "
                    + port + " while reconcile is running");
        } catch (ConnectException ignored) {
        } catch (Exception e) {
            fail("Unexpected exception when probing port " + port + ": " + e);
        }
    }

    private int findFreePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void insertRepositoryState(String repo) throws Exception {
        Connection conn = pool.acquire();
        try {
            conn.createStatement().executeUpdate(
                    "INSERT INTO repository_state (repository, last_sequence_no, last_event_time) "
                    + "VALUES ('" + repo + "', 0, NOW()) ON CONFLICT (repository) DO NOTHING");
        } finally {
            pool.release(conn);
        }
    }

    private String buildAppConfigJson(int port, DatabaseConfig db) {
        return String.format(
                "{\"server\":{\"port\":%d},"
                + "\"auth\":{\"enabled\":false},"
                + "\"database\":{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":5},"
                + "\"indexer\":{\"reconcileConcurrency\":1,\"reconcileTimeoutSeconds\":10,"
                +           "\"retentionDays\":7,\"pruneIntervalHours\":6},"
                + "\"plugin\":{\"providerId\":\"test\",\"properties\":{}}}",
                port, db.getUrl(), db.getUser(), db.getPassword());
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":10}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}

