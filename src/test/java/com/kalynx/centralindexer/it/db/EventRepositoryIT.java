package com.kalynx.centralindexer.it.db;

import com.google.gson.Gson;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link EventRepository} against a real PostgreSQL container.
 */
@RequiresDocker
class EventRepositoryIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private EventRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        container = new PostgresTestContainer();
        pool = new ConnectionPool(buildConfig(container, 25));
        new DatabaseInitializer(pool).init();
        repo = new EventRepository(pool);
    }

    @AfterEach
    void tearDown() {
        pool.close();
        container.close();
    }

    @Test
    void concurrentInsertsProduceUniqueSequenceNos() throws Exception {
        int threadCount = 20;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        List<Long> seqNos = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final String deliveryId = "concurrent-" + i;
            threads.add(Thread.ofVirtual().start(() -> {
                ready.countDown();
                try {
                    go.await();
                    Optional<ReviewEvent> stored = repo.insert(testEvent("owner/concurrent", deliveryId));
                    stored.ifPresent(e -> seqNos.add(e.sequenceNo()));
                } catch (Exception e) {
                    errors.add(e);
                }
            }));
        }

        ready.await();
        go.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }

        assertTrue(errors.isEmpty(), "No thread should throw: " + errors);
        assertEquals(threadCount, seqNos.size(), "All inserts should succeed");

        List<Long> sorted = seqNos.stream().sorted().collect(Collectors.toList());
        for (int i = 0; i < threadCount; i++) {
            assertEquals(i + 1L, sorted.get(i), "Sequence numbers must be consecutive with no gaps");
        }
    }

    @Test
    void pgNotifyPayloadMatchesInsertedEvent() throws Exception {
        Gson gson = GsonFactory.getInstance();

        Connection listenConn = DriverManager.getConnection(
                container.getJdbcUrl(), container.getUser(), container.getPassword());
        try {
            listenConn.createStatement().execute("LISTEN events");

            ReviewEvent event = testEvent("owner/notify-test", "delivery-notify-1");
            Optional<ReviewEvent> stored = repo.insert(event);
            assertTrue(stored.isPresent());

            PGConnection pgConn = listenConn.unwrap(PGConnection.class);
            listenConn.createStatement().execute("SELECT 1");
            PGNotification[] notifications = pgConn.getNotifications(5000);

            assertNotNull(notifications, "Should have received a notification");
            assertEquals(1, notifications.length);

            ReviewEvent notified = gson.fromJson(notifications[0].getParameter(), ReviewEvent.class);
            assertEquals(stored.get().sequenceNo(), notified.sequenceNo());
            assertEquals(stored.get().repository(), notified.repository());
            assertEquals(stored.get().eventType(), notified.eventType());
        } finally {
            listenConn.close();
        }
    }

    @Test
    void duplicateDeliveryIdWritesOnlyOneRow() throws Exception {
        ReviewEvent event = testEvent("owner/dedup-test", "delivery-dedup-1");

        Optional<ReviewEvent> first  = repo.insert(event);
        Optional<ReviewEvent> second = repo.insert(event);

        assertTrue(first.isPresent(),   "First insert should succeed");
        assertFalse(second.isPresent(), "Second insert with same deliveryId should be suppressed");

        Connection conn = pool.acquire();
        try (ResultSet rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM events WHERE repository = 'owner/dedup-test'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "Only one row should exist for the deduplicated event");
        } finally {
            pool.release(conn);
        }
    }

    @Test
    void queryAfterPruneRespectsRetentionWindow() throws Exception {
        Instant backdated = Instant.parse("2020-01-01T00:00:00Z");
        ReviewEvent old = new ReviewEvent(0L, backdated, "owner/prune-test",
                EventType.REVIEW_CREATED, null, null, "delivery-old-1", Map.of());
        Optional<ReviewEvent> stored = repo.insert(old);
        assertTrue(stored.isPresent());

        Connection conn = pool.acquire();
        try {
            conn.createStatement().executeUpdate(
                    "UPDATE events SET timestamp = '2020-01-01T00:00:00Z' " +
                    "WHERE repository = 'owner/prune-test'");
        } finally {
            pool.release(conn);
        }

        repo.pruneOlderThan(1);

        List<ReviewEvent> remaining = repo.queryEvents("owner/prune-test", 0, 100);
        assertTrue(remaining.isEmpty(), "Backdated event should be absent after pruning");
    }

    @Test
    void hasEventAtReturnsFalseAfterPrune() throws Exception {
        Instant backdated = Instant.parse("2020-01-01T00:00:00Z");
        ReviewEvent old = new ReviewEvent(0L, backdated, "owner/has-prune-test",
                EventType.REVIEW_CREATED, null, null, "delivery-has-1", Map.of());
        Optional<ReviewEvent> stored = repo.insert(old);
        assertTrue(stored.isPresent());
        long seqNo = stored.get().sequenceNo();

        Connection conn = pool.acquire();
        try {
            conn.createStatement().executeUpdate(
                    "UPDATE events SET timestamp = '2020-01-01T00:00:00Z' " +
                    "WHERE repository = 'owner/has-prune-test'");
        } finally {
            pool.release(conn);
        }

        repo.pruneOlderThan(1);

        assertFalse(repo.hasEventAt("owner/has-prune-test", seqNo),
                "hasEventAt should return false after the event is pruned");
    }

    private ReviewEvent testEvent(String repository, String deliveryId) {
        return new ReviewEvent(0L, Instant.now(), repository,
                EventType.REVIEW_CREATED, null, null, deliveryId, Map.of());
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

