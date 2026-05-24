package com.kalynx.centralindexer.sse;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
/**
 * Unit tests for {@link PublisherRegistry}.
 */
class PublisherRegistryTest {
    @Test
    void publisherCreatedOnFirstSubscribe() {
        PublisherRegistry registry = new PublisherRegistry();
        assertEquals(0, registry.publisherCount());
        registry.subscribe("owner/repo", noopSubscriber());
        assertEquals(1, registry.publisherCount());
    }
    @Test
    void publisherRemovedOnLastUnsubscribe() throws Exception {
        PublisherRegistry registry = new PublisherRegistry();
        CountDownLatch subscribed = new CountDownLatch(1);
        AtomicReference<Flow.Subscription> subRef = new AtomicReference<>();
        registry.subscribe("owner/repo", new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                subRef.set(s);
                s.request(Long.MAX_VALUE);
                subscribed.countDown();
            }
            @Override public void onNext(ReviewEvent item) {}
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        });
        subscribed.await(2, TimeUnit.SECONDS);
        subRef.get().cancel();
        Thread.sleep(100);
        assertEquals(0, registry.publisherCount(), "Publisher must be removed after last subscriber cancels");
    }
    @Test
    void publishToAbsentRepositoryIsNoOp() {
        PublisherRegistry registry = new PublisherRegistry();
        ReviewEvent event = testEvent("owner/absent");
        registry.publish(event);
        assertEquals(0, registry.publisherCount(), "No publisher must be created by publish()");
    }
    @Test
    void multipleSubscribersReceiveSameEvent() throws Exception {
        PublisherRegistry registry = new PublisherRegistry();
        BlockingQueue<ReviewEvent> queue1 = new LinkedBlockingQueue<>();
        BlockingQueue<ReviewEvent> queue2 = new LinkedBlockingQueue<>();
        registry.subscribe("owner/multi", capturingSubscriber(queue1));
        registry.subscribe("owner/multi", capturingSubscriber(queue2));
        Thread.sleep(50);
        ReviewEvent event = testEvent("owner/multi");
        registry.publish(event);
        ReviewEvent received1 = queue1.poll(3, TimeUnit.SECONDS);
        ReviewEvent received2 = queue2.poll(3, TimeUnit.SECONDS);
        assertNotNull(received1, "Subscriber 1 must receive the event");
        assertNotNull(received2, "Subscriber 2 must receive the event");
        assertEquals(event.deliveryId(), received1.deliveryId());
        assertEquals(event.deliveryId(), received2.deliveryId());
    }
    @Test
    void slowSubscriberDroppedPublisherContinues() throws Exception {
        PublisherRegistry registry = new PublisherRegistry();
        CountDownLatch slowDropped = new CountDownLatch(1);
        registry.subscribe("owner/slow", new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {}
            @Override
            public void onNext(ReviewEvent item) {}
            @Override
            public void onError(Throwable t) { slowDropped.countDown(); }
            @Override
            public void onComplete() {}
        });
        BlockingQueue<ReviewEvent> fastQueue = new LinkedBlockingQueue<>();
        registry.subscribe("owner/slow", capturingSubscriber(fastQueue));
        Thread.sleep(50);
        for (int i = 0; i < 20; i++) {
            registry.publish(testEvent("owner/slow"));
        }
        assertTrue(slowDropped.await(5, TimeUnit.SECONDS), "Slow subscriber must be dropped via onError");
        ReviewEvent fastReceived = fastQueue.poll(5, TimeUnit.SECONDS);
        assertNotNull(fastReceived, "Fast subscriber must continue receiving events");
    }
    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }
    private ReviewEvent testEvent(String repo) {
        return new ReviewEvent(1L, Instant.now(), repo,
                EventType.REVIEW_CREATED, null, null, "delivery-" + System.nanoTime(), Map.of());
    }
    private Flow.Subscriber<ReviewEvent> noopSubscriber() {
        return new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ReviewEvent item) {}
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        };
    }
    private Flow.Subscriber<ReviewEvent> capturingSubscriber(BlockingQueue<ReviewEvent> queue) {
        return new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ReviewEvent item) { queue.offer(item); }
            @Override public void onError(Throwable t) {}
            @Override public void onComplete() {}
        };
    }
}