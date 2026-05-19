package com.kalynx.centralindexer.sse;

import com.kalynx.centralindexer.model.ReviewEvent;

/**
 * Manages per-repository {@link java.util.concurrent.SubmissionPublisher} instances and
 * fans out stored events to all active SSE subscribers for that repository.
 *
 * <p>This stub fulfils the {@link com.kalynx.centralindexer.plugin.EventSinkImpl} dependency
 * for Milestone 3. The full SSE infrastructure — subscriber management, slow-consumer
 * drop policy, and publisher lifecycle — is implemented in Milestone 5.
 */
public final class PublisherRegistry {

    /**
     * Publishes {@code event} to all SSE subscribers watching {@code event.repository()}.
     *
     * <p>If no publisher exists for the repository the call is a no-op.
     *
     * @param event the event to fan out; must not be {@code null}
     */
    public void publish(ReviewEvent event) {
    }
}

