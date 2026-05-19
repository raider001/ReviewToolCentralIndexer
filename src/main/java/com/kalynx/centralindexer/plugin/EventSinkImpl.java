package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.exception.EventQueuedForRetryException;
import com.kalynx.centralindexer.exception.RetryQueueFullException;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Core implementation of {@link EventSink}.
 *
 * <p>Each call to {@link #submit} persists the event via {@link EventRepository#insert}.
 * A stored event (non-empty result) is immediately forwarded to {@link PublisherRegistry}
 * for fan-out to connected SSE clients. A duplicate delivery ID results in an empty
 * Optional — nothing is published.
 *
 * <p>When a {@link RetryQueue} is configured and {@link EventRepository#insert} throws
 * {@link SQLException}, the event is offered to the retry queue:
 * <ul>
 *   <li>If accepted ({@code offer()} returns {@code true}), an
 *       {@link EventQueuedForRetryException} is thrown so the webhook dispatcher can
 *       respond with {@code 202 Accepted}.</li>
 *   <li>If the queue is full ({@code offer()} returns {@code false}), a
 *       {@link RetryQueueFullException} is thrown so the dispatcher can respond with
 *       {@code 503 Service Unavailable}.</li>
 * </ul>
 */
public final class EventSinkImpl implements EventSink {

    private static final Logger log = LoggerFactory.getLogger(EventSinkImpl.class);

    private final EventRepository repository;
    private final PublisherRegistry publisherRegistry;
    private final RetryQueue retryQueue;

    /**
     * Constructs an {@code EventSinkImpl} without a retry queue.
     * {@link SQLException} is wrapped in a {@link RuntimeException} and rethrown.
     *
     * @param repository        the event repository for persistence
     * @param publisherRegistry the registry that fans events out to SSE subscribers
     */
    public EventSinkImpl(EventRepository repository, PublisherRegistry publisherRegistry) {
        this(repository, publisherRegistry, null);
    }

    /**
     * Constructs an {@code EventSinkImpl} with an optional retry queue.
     *
     * @param repository        the event repository for persistence
     * @param publisherRegistry the registry that fans events out to SSE subscribers
     * @param retryQueue        the retry queue to use on {@link SQLException}, or
     *                          {@code null} to rethrow immediately
     */
    public EventSinkImpl(EventRepository repository, PublisherRegistry publisherRegistry,
                         RetryQueue retryQueue) {
        this.repository = repository;
        this.publisherRegistry = publisherRegistry;
        this.retryQueue = retryQueue;
    }

    /**
     * Persists the event and, if not a duplicate, publishes it to all SSE subscribers.
     *
     * @param event the event to submit; must not be {@code null}
     * @throws EventQueuedForRetryException if a {@link SQLException} was caught and the
     *         event was successfully enqueued for retry
     * @throws RetryQueueFullException if a {@link SQLException} was caught and the retry
     *         queue is at capacity
     * @throws RuntimeException wrapping any {@link SQLException} when no retry queue is
     *         configured, or wrapping {@link InterruptedException}
     */
    @Override
    public void submit(ReviewEvent event) {
        try {
            repository.insert(event).ifPresentOrElse(
                stored -> {
                    log.info("Event persisted and published: type='{}' repo='{}' reviewId='{}' seq={}",
                            stored.eventType(), stored.repository(), stored.reviewId(), stored.sequenceNo());
                    publisherRegistry.publish(stored);
                },
                () -> log.info("Duplicate event ignored: deliveryId='{}' repo='{}'",
                        event.deliveryId(), event.repository())
            );
        } catch (SQLException e) {
            handleSqlException(e, event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while persisting event", e);
        }
    }

    private void handleSqlException(SQLException e, ReviewEvent event) {
        if (retryQueue == null) {
            throw new RuntimeException("Failed to persist event for repository: " + event.repository(), e);
        }
        if (!retryQueue.offer(event)) {
            throw new RetryQueueFullException(
                    "Retry queue is full; cannot accept event for repository: " + event.repository());
        }
        throw new EventQueuedForRetryException(
                "Event queued for retry for repository: " + event.repository());
    }
}

