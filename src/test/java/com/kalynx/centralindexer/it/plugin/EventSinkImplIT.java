package com.kalynx.centralindexer.it.plugin;

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
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link EventSinkImpl} against a real PostgreSQL container.
 */
@RequiresDocker
class EventSinkImplIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private EventSinkImpl sink;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = new ConnectionPool(buildConfig(container, 5));
        new DatabaseInitializer(pool).init();
        sink = new EventSinkImpl(new EventRepository(pool), new PublisherRegistry());
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    @Test
    void submitInsertsRowAndAssignsSequenceNo() throws Exception {
        ReviewEvent event = new ReviewEvent(0L, Instant.now(), "owner/sink-test",
                EventType.REVIEW_CREATED, null, null, "delivery-sink-1", Map.of());

        sink.submit(event);

        Connection conn = pool.acquire();
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT sequence_no FROM events WHERE repository = 'owner/sink-test'")) {
            assertTrue(rs.next(), "Row should exist after submit");
            assertEquals(1L, rs.getLong("sequence_no"), "Sequence number should be 1");
        } finally {
            pool.release(conn);
        }
    }

    private DatabaseConfig buildConfig(PostgresTestContainer container, int poolSize) {
        String json = """
                {
                  "url": "%s",
                  "user": "%s",
                  "password": "%s",
                  "poolSize": %d
                }
                """.formatted(container.getJdbcUrl(), container.getUser(),
                container.getPassword(), poolSize);
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}

