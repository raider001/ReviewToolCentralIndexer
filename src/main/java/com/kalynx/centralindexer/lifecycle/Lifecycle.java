package com.kalynx.centralindexer.lifecycle;

/**
 * Common start/stop contract for long-lived application components.
 *
 * <p>Implementations must be idempotent: calling {@link #start()} or {@link #stop()}
 * more than once must not throw and must leave the component in a consistent state.
 */
public interface Lifecycle {
    void start() throws Exception;
    void stop();
}
