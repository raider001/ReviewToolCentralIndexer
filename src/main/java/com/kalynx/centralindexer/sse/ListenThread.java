package com.kalynx.centralindexer.sse;

import com.google.gson.Gson;
import com.kalynx.centralindexer.config.DatabaseConfig;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.ReviewEvent;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * Listens for PostgreSQL {@code pg_notify} notifications on the {@code events} channel and
 * forwards each payload to {@link PublisherRegistry} for SSE fan-out.
 *
 * <p>A dedicated JDBC connection is opened outside the shared pool so that the blocking
 * {@code LISTEN} socket does not starve other callers.
 *
 * <p>{@link #start()} spawns a virtual thread. {@link #stop()} interrupts it and waits
 * up to 5 seconds for it to exit.
 */
public final class ListenThread {

    private static final Logger log = LoggerFactory.getLogger(ListenThread.class);
    private static final int POLL_TIMEOUT_MS = 500;

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final PublisherRegistry registry;
    private final Gson gson;

    private volatile Thread thread;

    /**
     * Constructs a {@code ListenThread} that connects to the database described by
     * {@code config} and forwards notifications to {@code registry}.
     *
     * @param config   database connection configuration
     * @param registry the registry to publish decoded events to
     */
    public ListenThread(DatabaseConfig config, PublisherRegistry registry) {
        this.jdbcUrl = config.getUrl();
        this.user = config.getUser();
        this.password = config.getPassword();
        this.registry = registry;
        this.gson = GsonFactory.getInstance();
    }

    /**
     * Starts the LISTEN loop on a new virtual thread.
     */
    public void start() {
        thread = Thread.ofVirtual().name("listen-thread").start(this::run);
    }

    /**
     * Interrupts the LISTEN loop and waits up to 5 seconds for the thread to exit.
     */
    public void stop() {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            conn.createStatement().execute("LISTEN events");
            PGConnection pgConn = conn.unwrap(PGConnection.class);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    PGNotification[] notifications = pgConn.getNotifications(POLL_TIMEOUT_MS);
                    if (notifications != null) {
                        for (PGNotification n : notifications) {
                            processNotification(n);
                        }
                    }
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    log.error("Error polling pg_notify notifications, listen thread stopping", e);
                    break;
                }
            }
        } catch (Exception e) {
            if (!Thread.currentThread().isInterrupted()) {
                log.error("Listen thread terminated unexpectedly", e);
            }
        }
    }

    private void processNotification(PGNotification notification) {
        try {
            ReviewEvent event = gson.fromJson(notification.getParameter(), ReviewEvent.class);
            registry.publish(event);
        } catch (Exception e) {
            log.warn("Failed to deserialise notification payload: {}", e.getMessage());
        }
    }
}

