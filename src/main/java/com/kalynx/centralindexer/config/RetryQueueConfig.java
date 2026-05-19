package com.kalynx.centralindexer.config;

/**
 * Retry queue configuration for in-memory buffering of webhook events when
 * PostgreSQL is temporarily unavailable.
 */
public final class RetryQueueConfig {

    private int maxDepth = 1000;
    private int maxRetryMinutes = 5;

    /**
     * Returns the maximum number of events that can be held in the retry queue
     * simultaneously. When this limit is reached, new submissions return {@code false}
     * and the webhook handler responds with {@code 503}.
     *
     * @return the maximum queue depth (default 1000)
     */
    public int getMaxDepth() {
        return maxDepth;
    }

    /**
     * Returns the maximum number of minutes an event will be retried before it is
     * discarded with a warning log.
     *
     * @return the retry duration cap in minutes (default 5)
     */
    public int getMaxRetryMinutes() {
        return maxRetryMinutes;
    }
}

