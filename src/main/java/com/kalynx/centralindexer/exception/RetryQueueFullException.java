package com.kalynx.centralindexer.exception;

/**
 * Thrown by {@link com.kalynx.centralindexer.plugin.EventSinkImpl#submit} when
 * the retry queue is at capacity and cannot accept an additional event.
 *
 * <p>The {@link com.kalynx.centralindexer.http.WebhookDispatcher} catches this
 * exception and responds with {@code 503 Service Unavailable}.
 */
public final class RetryQueueFullException extends RuntimeException {

    /**
     * Constructs a {@code RetryQueueFullException} with the given detail message.
     *
     * @param message a description of why the queue is full
     */
    public RetryQueueFullException(String message) {
        super(message);
    }
}

