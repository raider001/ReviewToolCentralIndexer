package com.kalynx.centralindexer.exception;

/**
 * Thrown by {@link com.kalynx.centralindexer.plugin.EventSinkImpl#submit} when a
 * {@link java.sql.SQLException} is caught and the event has been successfully enqueued
 * in the retry queue (i.e. {@code RetryQueue.offer()} returned {@code true}).
 *
 * <p>The {@link com.kalynx.centralindexer.http.WebhookDispatcher} catches this
 * exception and responds with {@code 202 Accepted}, signalling to the provider that
 * the webhook was received and will be persisted once the database recovers.
 */
public final class EventQueuedForRetryException extends RuntimeException {

    /**
     * Constructs an {@code EventQueuedForRetryException} with the given detail message.
     *
     * @param message a description of the event that was queued
     */
    public EventQueuedForRetryException(String message) {
        super(message);
    }
}

