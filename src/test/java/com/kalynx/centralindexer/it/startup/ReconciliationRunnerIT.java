package com.kalynx.centralindexer.it.startup;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.plugin.EventSinkImpl;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.kalynx.centralindexer.startup.ReconciliationRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for {@link ReconciliationRunner} against a real PostgreSQL container.
 */
@RequiresDocker
class ReconciliationRunnerIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private EventRepository eventRepo;
    private EventSinkImpl sink;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        DatabaseConfig dbConfig = buildDbConfig(container);
        pool = new ConnectionPool(dbConfig);
        new DatabaseInitializer(pool).init();
        eventRepo = new EventRepository(pool);
        sink = new EventSinkImpl(eventRepo, new PublisherRegistry());
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    @Test
    void backfilledEventsStoredAfterReconcile() throws Exception {
        String repo = "owner/recon-test";
        insertRepositoryState(repo, Instant.now());

        ProviderPlugin plugin = mock(ProviderPlugin.class);
        doAnswer(inv -> {
            sink.submit(new ReviewEvent(0L, Instant.now(), repo,
                    EventType.REVIEW_CREATED, null, null, "delivery-recon-1", Map.of()));
            return null;
        }).when(plugin).reconcile(eq(repo), any());

        new ReconciliationRunner(buildConfig(5, 10, 7), eventRepo, plugin).run();

        Connection conn = pool.acquire();
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM events WHERE repository = '" + repo + "'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "Event submitted during reconcile() must be persisted");
        } finally {
            pool.release(conn);
        }
    }

    @Test
    void duplicateFromReconcileNotDoubleInserted() throws Exception {
        String repo = "owner/dup-recon";
        insertRepositoryState(repo, Instant.now());

        ProviderPlugin plugin = mock(ProviderPlugin.class);
        doAnswer(inv -> {
            ReviewEvent event = new ReviewEvent(0L, Instant.now(), repo,
                    EventType.REVIEW_CREATED, null, null, "delivery-dup-1", Map.of());
            sink.submit(event);
            sink.submit(event);
            return null;
        }).when(plugin).reconcile(eq(repo), any());

        new ReconciliationRunner(buildConfig(5, 10, 7), eventRepo, plugin).run();

        Connection conn = pool.acquire();
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM events WHERE repository = '" + repo + "'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "Duplicate delivery_id must yield exactly one row");
        } finally {
            pool.release(conn);
        }
    }

    private void insertRepositoryState(String repo, Instant lastEventTime) throws Exception {
        Connection conn = pool.acquire();
        try {
            conn.createStatement().executeUpdate(
                    "INSERT INTO repository_state (repository, last_sequence_no, last_event_time) "
                    + "VALUES ('" + repo + "', 0, '" + lastEventTime + "') "
                    + "ON CONFLICT (repository) DO NOTHING");
        } finally {
            pool.release(conn);
        }
    }

    private AppConfig buildConfig(int concurrency, int timeoutSeconds, int retentionDays) {
        String json = String.format(
                "{\"indexer\":{\"reconcileConcurrency\":%d,\"reconcileTimeoutSeconds\":%d,"
                + "\"retentionDays\":%d}}",
                concurrency, timeoutSeconds, retentionDays);
        return GsonFactory.getInstance().fromJson(json, AppConfig.class);
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":10}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}

