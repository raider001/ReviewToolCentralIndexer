package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.exception.EventQueuedForRetryException;
import com.kalynx.centralindexer.exception.RetryQueueFullException;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EventSinkImpl}.
 */
class EventSinkImplTest {

    @Test
    void submitPersistsAndPublishes() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        PublisherRegistry registry = mock(PublisherRegistry.class);
        ReviewEvent input = testEvent("delivery-1");
        ReviewEvent stored = testEventWithSeqNo(1L, "delivery-1");
        when(repo.insert(input)).thenReturn(Optional.of(stored));

        new EventSinkImpl(repo, registry).submit(input);

        verify(repo).insert(input);
        verify(registry).publish(stored);
    }

    @Test
    void duplicateSuppressedNothingPublished() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        PublisherRegistry registry = mock(PublisherRegistry.class);
        ReviewEvent input = testEvent("delivery-dup");
        when(repo.insert(input)).thenReturn(Optional.empty());

        new EventSinkImpl(repo, registry).submit(input);

        verify(repo).insert(input);
        verify(registry, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sqlExceptionTriggersRetryQueueOffer() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        PublisherRegistry registry = mock(PublisherRegistry.class);
        RetryQueue queue = mock(RetryQueue.class);
        ReviewEvent event = testEvent("delivery-sql");

        when(repo.insert(any())).thenThrow(new SQLException("DB down"));
        when(queue.offer(event)).thenReturn(true);

        assertThrows(EventQueuedForRetryException.class,
                () -> new EventSinkImpl(repo, registry, queue).submit(event));

        verify(queue).offer(event);
    }

    @Test
    void fullQueueThrowsRetryQueueFullException() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        PublisherRegistry registry = mock(PublisherRegistry.class);
        RetryQueue queue = mock(RetryQueue.class);
        ReviewEvent event = testEvent("delivery-full");

        when(repo.insert(any())).thenThrow(new SQLException("DB down"));
        when(queue.offer(event)).thenReturn(false);

        assertThrows(RetryQueueFullException.class,
                () -> new EventSinkImpl(repo, registry, queue).submit(event));
    }

    private ReviewEvent testEvent(String deliveryId) {
        return new ReviewEvent(0L, Instant.parse("2026-05-19T10:00:00Z"), "owner/repo",
                EventType.REVIEW_CREATED, null, null, deliveryId, Map.of());
    }

    private ReviewEvent testEventWithSeqNo(long seqNo, String deliveryId) {
        return new ReviewEvent(seqNo, Instant.parse("2026-05-19T10:00:00Z"), "owner/repo",
                EventType.REVIEW_CREATED, null, null, deliveryId, Map.of());
    }
}
