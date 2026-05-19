package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.config.RetryQueueConfig;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RetryQueue}.
 */
class RetryQueueTest {

    @Test
    void offerReturnsFalseAtCapacity() {
        EventRepository repo = mock(EventRepository.class);
        RetryQueue queue = new RetryQueue(parseConfig(3, 5), repo, mock(PublisherRegistry.class));

        ReviewEvent event = testEvent("d-1");
        assertTrue(queue.offer(event));
        assertTrue(queue.offer(event));
        assertTrue(queue.offer(event));
        assertFalse(queue.offer(event), "offer() must return false once the queue is at capacity");
    }

    @Test
    void eventRetriedUntilInsertSucceeds() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        PublisherRegistry registry = mock(PublisherRegistry.class);
        ReviewEvent stored = testEventWithSeqNo(1L, "d-retry");

        when(repo.insert(any()))
                .thenThrow(new SQLException("down"))
                .thenThrow(new SQLException("down"))
                .thenReturn(Optional.of(stored));

        CountDownLatch published = new CountDownLatch(1);
        doAnswer(inv -> { published.countDown(); return null; }).when(registry).publish(any());

        LongSupplier clock = () -> 0L;
        List<Long> delays = new ArrayList<>();
        RetryQueue queue = new RetryQueue(parseConfig(10, 5), repo, registry, clock, ms -> delays.add(ms));
        queue.start();
        queue.offer(testEvent("d-retry"));

        assertTrue(published.await(2, TimeUnit.SECONDS), "publish() must be called after the third attempt");
        queue.shutdown();

        verify(registry).publish(stored);
    }

    @Test
    void eventDiscardedAfterMaxRetryDuration() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        PublisherRegistry registry = mock(PublisherRegistry.class);
        when(repo.insert(any())).thenThrow(new SQLException("always fails"));

        long maxRetryMs = 60_000L;
        AtomicLong fakeTime = new AtomicLong(0L);
        LongSupplier clock = fakeTime::get;
        RetryQueue.SleepAction sleeper = ms -> fakeTime.addAndGet(maxRetryMs + 1_000);

        RetryQueue queue = new RetryQueue(parseConfig(10, 1), repo, registry, clock, sleeper);
        queue.start();
        queue.offer(testEvent("d-discard"));

        Thread.sleep(500);
        queue.shutdown();

        verify(registry, never()).publish(any());
    }

    @Test
    void backOffDelaysAreExponential() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        PublisherRegistry registry = mock(PublisherRegistry.class);
        ReviewEvent stored = testEventWithSeqNo(1L, "d-exp");

        AtomicInteger attempts = new AtomicInteger();
        when(repo.insert(any())).thenAnswer(inv -> {
            if (attempts.incrementAndGet() <= 3) {
                throw new SQLException("down");
            }
            return Optional.of(stored);
        });

        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> { done.countDown(); return null; }).when(registry).publish(any());

        List<Long> delays = new ArrayList<>();
        RetryQueue queue = new RetryQueue(parseConfig(10, 5), repo, registry, () -> 0L, ms -> delays.add(ms));
        queue.start();
        queue.offer(testEvent("d-exp"));

        assertTrue(done.await(2, TimeUnit.SECONDS));
        queue.shutdown();

        assertTrue(delays.get(0) >= 1_000, "First delay must be >= 1 s");
        assertTrue(delays.get(1) >= 2_000, "Second delay must be >= 2 s");
        assertTrue(delays.get(2) >= 4_000, "Third delay must be >= 4 s");
    }

    @Test
    void backOffCapsAt30Seconds() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        PublisherRegistry registry = mock(PublisherRegistry.class);
        ReviewEvent stored = testEventWithSeqNo(1L, "d-cap");

        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        when(repo.insert(any())).thenAnswer(inv -> {
            if (attempts.incrementAndGet() <= 10) {
                throw new SQLException("still down");
            }
            done.countDown();
            return Optional.of(stored);
        });
        doAnswer(inv -> null).when(registry).publish(any());

        List<Long> delays = new ArrayList<>();
        RetryQueue queue = new RetryQueue(parseConfig(10, 5), repo, registry, () -> 0L, ms -> delays.add(ms));
        queue.start();
        queue.offer(testEvent("d-cap"));

        assertTrue(done.await(2, TimeUnit.SECONDS));
        queue.shutdown();

        assertTrue(delays.stream().allMatch(d -> d <= 30_000),
                "Every back-off delay must be <= 30_000 ms (capped at 30 s)");
    }

    private RetryQueueConfig parseConfig(int maxDepth, int maxRetryMinutes) {
        String json = String.format("{\"maxDepth\":%d,\"maxRetryMinutes\":%d}", maxDepth, maxRetryMinutes);
        return GsonFactory.getInstance().fromJson(json, RetryQueueConfig.class);
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

