package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.sse.PublisherRegistry;

import java.sql.SQLException;

/**
 * Core implementation of {@link EventSink}.
 *
 * <p>Each call to {@link #submit} persists the event via {@link EventRepository#insert}.
 * A stored event (non-empty result) is immediately forwarded to {@link PublisherRegistry}
 * for fan-out to connected SSE clients. A duplicate delivery ID ({@link java.util.Optional#empty()})
 * is silently dropped — nothing is published.
 */
public final class EventSinkImpl implements EventSink {

    private final EventRepository repository;
    private final PublisherRegistry publisherRegistry;

    /**
     * Constructs an {@code EventSinkImpl}.
     *
     * @param repository       the event repository for persistence
     * @param publisherRegistry the registry that fans events out to SSE subscribers
     */
    public EventSinkImpl(EventRepository repository, PublisherRegistry publisherRegistry) {
        this.repository = repository;
        this.publisherRegistry = publisherRegistry;
    }

    /**
     * Persists the event and, if it was not a duplicate, publishes it to all SSE subscribers.
     *
     * @param event the event to submit; must not be {@code null}
     * @throws RuntimeException wrapping any {@link SQLException} from the persistence layer,
     *                          or if the calling thread is interrupted during pool acquisition
     */
    @Override
    public void submit(ReviewEvent event) {
        try {
            repository.insert(event).ifPresent(publisherRegistry::publish);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to persist event for repository: " + event.repository(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while persisting event", e);
        }
    }
}

