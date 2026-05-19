package com.kalynx.centralindexer.it.startup;

import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.it.support.PostgresTestContainer;
import com.kalynx.centralindexer.it.support.RequiresDocker;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.startup.PruneScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Integration test for {@link PruneScheduler} against a real PostgreSQL container.
 */
@RequiresDocker
class PruneSchedulerIT {

    private PostgresTestContainer container;
    private ConnectionPool pool;
    private EventRepository eventRepo;

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
        pool.close();
        container.close();
    }

    @Test
    void pruneRunsAndRemovesExpiredRows() throws Exception {
        String repo = "owner/prune-it-test";
        eventRepo.insert(new ReviewEvent(0L, Instant.now(), repo,
                EventType.REVIEW_CREATED, null, null, "delivery-prune-it-1", Map.of()));

        Connection conn = pool.acquire();
        try {
            conn.createStatement().executeUpdate(
                    "UPDATE events SET timestamp = '2020-01-01T00:00:00Z' "
                    + "WHERE repository = '" + repo + "'");
        } finally {
            pool.release(conn);
        }

        PruneScheduler scheduler = new PruneScheduler(eventRepo, 7, 6);
        scheduler.start();
        Thread.sleep(500);
        scheduler.shutdown();

        Connection verifyConn = pool.acquire();
        try (ResultSet rs = verifyConn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM events WHERE repository = '" + repo + "'")) {
            rs.next();
            assertEquals(0, rs.getInt(1), "Event older than retention window must be pruned");
        } finally {
            pool.release(verifyConn);
        }
    }

    private DatabaseConfig buildDbConfig(PostgresTestContainer c) {
        String json = String.format(
                "{\"url\":\"%s\",\"user\":\"%s\",\"password\":\"%s\",\"poolSize\":10}",
                c.getJdbcUrl(), c.getUser(), c.getPassword());
        return GsonFactory.getInstance().fromJson(json, DatabaseConfig.class);
    }
}

