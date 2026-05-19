package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.db.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Periodically deletes events older than the configured retention window.
 *
 * <p>On {@link #start()}, {@code pruneOlderThan(retentionDays)} is called immediately
 * on a new virtual thread. The thread then sleeps for {@code pruneIntervalHours} hours
 * before repeating. {@link #shutdown()} interrupts the loop and waits up to 5 seconds
 * for the thread to exit.
 */
public final class PruneScheduler {

    private static final Logger log = LoggerFactory.getLogger(PruneScheduler.class);

    private final EventRepository eventRepository;
    private final int retentionDays;
    private final int pruneIntervalHours;
    private volatile Thread thread;

    /**
     * Constructs a {@code PruneScheduler}.
     *
     * @param eventRepository    the repository used to delete old events
     * @param retentionDays      events older than this many days are removed
     * @param pruneIntervalHours interval between successive prune calls after the first
     */
    public PruneScheduler(EventRepository eventRepository, int retentionDays, int pruneIntervalHours) {
        this.eventRepository = eventRepository;
        this.retentionDays = retentionDays;
        this.pruneIntervalHours = pruneIntervalHours;
    }

    /**
     * Starts the pruning loop on a new virtual thread. The first prune happens immediately
     * before the initial sleep.
     */
    public void start() {
        thread = Thread.ofVirtual().name("prune-scheduler").start(this::runLoop);
    }

    /**
     * Interrupts the pruning loop and waits up to 5 seconds for the thread to exit.
     */
    public void shutdown() {
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

    private void runLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            pruneNow();
            try {
                Thread.sleep(TimeUnit.HOURS.toMillis(pruneIntervalHours));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void pruneNow() {
        try {
            eventRepository.pruneOlderThan(retentionDays);
            log.debug("Pruned events older than {} days", retentionDays);
        } catch (Exception e) {
            log.warn("Event pruning failed: {}", e.getMessage());
        }
    }
}

