package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.config.RetryQueueConfig;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Bounded in-memory queue that retries failed event persistence with exponential back-off.
 *
 * <p>When PostgreSQL is temporarily unavailable, {@link EventRepository#insert} throws
 * {@link SQLException}. {@link EventSinkImpl} offers the unpersistedEvent to this queue
 * via {@link #offer}. A background virtual thread then retries the insert using the
 * delay sequence {@code 1 s, 2 s, 4 s, 8 s, 16 s, 30 s (capped)}.
 *
 * <p>An event that cannot be persisted within {@code maxRetryMinutes} is discarded with a
 * {@code WARN} log containing the {@code deliveryId} and {@code repository}.
 *
 * <p>On successful persistence the stored event is forwarded to {@link PublisherRegistry}
 * so live SSE clients receive it as soon as the database recovers (behaviour 8.6).
 */
public final class RetryQueue {

    /**
     * Package-private sleep abstraction to enable deterministic unit tests.
     */
    @FunctionalInterface
    interface SleepAction {
        /**
         * Sleeps for the given number of milliseconds.
         *
         * @param millis the sleep duration in milliseconds
         * @throws InterruptedException if the sleeping thread is interrupted
         */
        void sleep(long millis) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(RetryQueue.class);
    private static final long[] BACKOFF_MILLIS = {1_000, 2_000, 4_000, 8_000, 16_000, 30_000};

    private final ArrayBlockingQueue<RetryEntry> queue;
    private final EventRepository repository;
    private final PublisherRegistry registry;
    private final long maxRetryMillis;
    private final LongSupplier clock;
    private final SleepAction sleeper;

    private volatile boolean running = true;
    private volatile Thread workerThread;

    /**
     * Constructs a production {@code RetryQueue}.
     *
     * @param config     the retry queue configuration
     * @param repository used to retry event persistence
     * @param registry   receives successfully persisted events for SSE fan-out
     */
    public RetryQueue(RetryQueueConfig config, EventRepository repository, PublisherRegistry registry) {
        this(config, repository, registry, System::currentTimeMillis, Thread::sleep);
    }

    RetryQueue(RetryQueueConfig config, EventRepository repository, PublisherRegistry registry,
               LongSupplier clock, SleepAction sleeper) {
        this.queue = new ArrayBlockingQueue<>(config.getMaxDepth());
        this.repository = repository;
        this.registry = registry;
        this.maxRetryMillis = (long) config.getMaxRetryMinutes() * 60_000L;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Offers an event for retry.
     *
     * @param event the event that could not be persisted
     * @return {@code true} if enqueued; {@code false} if the queue is at capacity or
     *         {@link #shutdown()} has been called
     */
    public boolean offer(ReviewEvent event) {
        if (!running) {
            return false;
        }
        return queue.offer(new RetryEntry(event, clock.getAsLong()));
    }

    /**
     * Starts the drain thread.
     */
    public void start() {
        workerThread = Thread.ofVirtual().name("retry-queue").start(this::drainLoop);
    }

    /**
     * Stops accepting new events and waits up to {@code maxRetryMinutes} for the
     * in-flight retry thread to complete before returning.
     */
    public void shutdown() {
        running = false;
        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(maxRetryMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                RetryEntry entry = queue.poll(100, TimeUnit.MILLISECONDS);
                if (entry == null) {
                    if (!running && queue.isEmpty()) {
                        break;
                    }
                    continue;
                }
                retryPersist(entry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void retryPersist(RetryEntry entry) {
        int attempt = 0;
        while (!Thread.currentThread().isInterrupted()) {
            long delay = BACKOFF_MILLIS[Math.min(attempt, BACKOFF_MILLIS.length - 1)];
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (clock.getAsLong() - entry.enqueuedTimeMillis > maxRetryMillis) {
                log.warn("Discarding event after retry timeout: repository='{}', deliveryId='{}'",
                        entry.event.repository(), entry.event.deliveryId());
                return;
            }

            try {
                repository.insert(entry.event).ifPresent(registry::publish);
                return;
            } catch (SQLException e) {
                log.debug("Retry {} for '{}' failed: {}", attempt + 1, entry.event.repository(), e.getMessage());
                attempt++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static final class RetryEntry {
        final ReviewEvent event;
        final long enqueuedTimeMillis;

        RetryEntry(ReviewEvent event, long enqueuedTimeMillis) {
            this.event = event;
            this.enqueuedTimeMillis = enqueuedTimeMillis;
        }
    }
}

