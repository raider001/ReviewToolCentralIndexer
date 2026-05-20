package com.kalynx.centralindexer.sse;

import com.kalynx.centralindexer.model.ReviewEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages per-repository {@link SubmissionPublisher} instances and fans out stored events
 * to all active SSE subscribers for that repository.
 *
 * <p>Each publisher has a maximum buffer capacity of 8 items per subscriber. A subscriber
 * whose buffer is full when {@link #publish} is called is dropped via
 * {@link Flow.Subscriber#onError} — the publisher is never back-pressured.
 *
 * <p>When the last subscriber for a repository cancels or terminates, the publisher is
 * removed from the registry map to prevent unbounded growth.
 */
public final class PublisherRegistry {

    private static final Logger log = LoggerFactory.getLogger(PublisherRegistry.class);
    private static final int BUFFER_CAPACITY = 8;
    static final String WILDCARD_REPO = "*";

    private final ConcurrentHashMap<String, PublisherEntry> entries = new ConcurrentHashMap<>();

    /**
     * Subscribes {@code subscriber} to the publisher for {@code repository}, creating the
     * publisher if it does not yet exist.
     *
     * @param repository the repository channel to subscribe to
     * @param subscriber the subscriber to receive events; must not be {@code null}
     */
    public void subscribe(String repository, Flow.Subscriber<? super ReviewEvent> subscriber) {
        PublisherEntry entry = entries.computeIfAbsent(repository, k -> new PublisherEntry());
        entry.subscriberCount.incrementAndGet();
        entry.publisher.subscribe(
                new WatchingSubscriber<>(subscriber, () -> onSubscriberTerminated(repository, entry)));
    }

    /**
     * Subscribes {@code subscriber} to all events across every repository.
     * The subscriber will receive events published for any repository.
     *
     * @param subscriber the subscriber to receive events; must not be {@code null}
     */
    public void subscribeAll(Flow.Subscriber<? super ReviewEvent> subscriber) {
        subscribe(WILDCARD_REPO, subscriber);
    }

    /**
     * Publishes {@code event} to all SSE subscribers watching {@code event.repository()},
     * and also to any wildcard subscribers registered via {@link #subscribeAll}.
     *
     * <p>If no publisher exists for the repository the call is a no-op. A subscriber whose
     * buffer is full is dropped via {@link Flow.Subscriber#onError}.
     *
     * @param event the event to fan out; must not be {@code null}
     */
    public void publish(ReviewEvent event) {
        offerToEntry(entries.get(event.repository()), event);
        offerToEntry(entries.get(WILDCARD_REPO), event);
    }

    /**
     * Returns the number of active per-repository publishers.
     *
     * @return active publisher count
     */
    int publisherCount() {
        return entries.size();
    }

    private void onSubscriberTerminated(String repository, PublisherEntry entry) {
        if (entry.subscriberCount.decrementAndGet() == 0) {
            if (entries.remove(repository, entry)) {
                entry.publisher.close();
            }
        }
    }

    private void offerToEntry(PublisherEntry entry, ReviewEvent event) {
        if (entry == null) {
            return;
        }
        entry.publisher.offer(event, (sub, item) -> {
            log.warn("Slow SSE subscriber dropped for repository '{}'", event.repository());
            sub.onError(new IllegalStateException(
                    "SSE buffer capacity exceeded for repository: " + event.repository()));
            return false;
        });
    }

    private static final class PublisherEntry {
        final SubmissionPublisher<ReviewEvent> publisher =
                new SubmissionPublisher<>(ForkJoinPool.commonPool(), BUFFER_CAPACITY);
        final AtomicInteger subscriberCount = new AtomicInteger(0);
    }

    private static final class WatchingSubscriber<T> implements Flow.Subscriber<T> {

        private final Flow.Subscriber<? super T> delegate;
        private final Runnable onTerminal;

        WatchingSubscriber(Flow.Subscriber<? super T> delegate, Runnable onTerminal) {
            this.delegate = delegate;
            this.onTerminal = onTerminal;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            delegate.onSubscribe(new WatchingSubscription(subscription, onTerminal));
        }

        @Override
        public void onNext(T item) {
            delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            delegate.onError(throwable);
            onTerminal.run();
        }

        @Override
        public void onComplete() {
            delegate.onComplete();
            onTerminal.run();
        }
    }

    private static final class WatchingSubscription implements Flow.Subscription {

        private final Flow.Subscription delegate;
        private final Runnable onCancel;

        WatchingSubscription(Flow.Subscription delegate, Runnable onCancel) {
            this.delegate = delegate;
            this.onCancel = onCancel;
        }

        @Override
        public void request(long n) {
            delegate.request(n);
        }

        @Override
        public void cancel() {
            delegate.cancel();
            onCancel.run();
        }
    }
}
