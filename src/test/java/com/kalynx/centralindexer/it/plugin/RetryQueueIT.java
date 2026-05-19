package com.kalynx.centralindexer.it.plugin;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.config.RetryQueueConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.exception.EventQueuedForRetryException;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.plugin.EventSinkImpl;
import com.kalynx.centralindexer.plugin.RetryQueue;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Integration test confirming that events are persisted after the database recovers
 * from a transient outage, and that no {@code 503} is returned to the caller when the
 * retry queue has capacity.
 */
@RequiresDocker
class RetryQueueIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = new ConnectionPool(buildDbConfig(container));
        new DatabaseInitializer(pool).init();
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    @Test
    void eventPersistedAfterDatabaseRecovers() throws Exception {
        EventRepository realRepo = new EventRepository(pool);
        PublisherRegistry registry = new PublisherRegistry();

        AtomicBoolean dbDown = new AtomicBoolean(true);
        EventRepository mockRepo = mock(EventRepository.class);
        doAnswer(inv -> {
            if (dbDown.get()) {
                throw new SQLException("Simulated DB outage");
            }
            return realRepo.insert(inv.<ReviewEvent>getArgument(0));
        }).when(mockRepo).insert(any());

        RetryQueueConfig config = GsonFactory.getInstance()
                .fromJson("{\"maxDepth\":100,\"maxRetryMinutes\":1}", RetryQueueConfig.class);
        RetryQueue retryQueue = new RetryQueue(config, mockRepo, registry);
        retryQueue.start();

        EventSinkImpl sink = new EventSinkImpl(mockRepo, registry, retryQueue);
        ReviewEvent event = new ReviewEvent(0L, Instant.now(), "owner/retry-it-test",
                EventType.REVIEW_CREATED, null, null, "delivery-retry-it-1", Map.of());

        assertThrows(EventQueuedForRetryException.class, () -> sink.submit(event),
                "Submit must throw EventQueuedForRetryException when DB is unavailable and queue has space");

        dbDown.set(false);

        Thread.sleep(3_000);
        retryQueue.shutdown();

        Connection conn = pool.acquire();
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM events WHERE delivery_id = 'delivery-retry-it-1'")) {
            rs.next();
            assertEquals(1, rs.getInt(1),
                    "Event must be persisted in the database after the DB recovers and retry succeeds");
        } finally {
            pool.release(conn);
        }
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":10}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}

