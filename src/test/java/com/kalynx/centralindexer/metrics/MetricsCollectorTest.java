package com.kalynx.centralindexer.metrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricsCollector}.
 */
class MetricsCollectorTest {

    private MetricsCollector metrics;

    @BeforeEach
    void setUp() {
        metrics = new MetricsCollector(null); // null pool — pool stats return -1
    }

    // --- connected clients ---

    @Test
    void getConnectedClients_initialState_returnsZero() {
        assertEquals(0, metrics.getConnectedClients());
    }

    @Test
    void incrementAndDecrement_connectedClients_updatesCount() {
        metrics.incrementConnectedClients();
        metrics.incrementConnectedClients();
        assertEquals(2, metrics.getConnectedClients());

        metrics.decrementConnectedClients();
        assertEquals(1, metrics.getConnectedClients());
    }

    // --- SSE event rate ---

    @Test
    void getSseWritersPerSecond_noEvents_returnsZero() {
        assertEquals(0.0, metrics.getSseWritersPerSecond(), 0.01);
    }

    @Test
    void getSseWritersPerSecond_recentEvents_countsAll() throws InterruptedException {
        metrics.recordSseEvent("test.event");
        metrics.recordSseEvent("test.event");
        metrics.recordSseEvent("test.event");

        double rate = metrics.getSseWritersPerSecond();
        assertEquals(3.0, rate, 0.01, "Three events recorded within 1s must count");
    }

    // --- latency percentiles ---

    @Test
    void getSseWriteLatencyP95_insufficientSamples_returnsMinusOne() {
        // fewer than 20 samples → -1
        for (int i = 0; i < 19; i++) {
            metrics.recordSseWriteLatency(10);
        }
        assertEquals(-1, metrics.getSseWriteLatencyP95());
    }

    @Test
    void getSseWriteLatencyP95_hundredSamples_computesCorrectly() {
        for (int i = 1; i <= 100; i++) {
            metrics.recordSseWriteLatency(i); // 1..100 ms
        }
        long p95 = metrics.getSseWriteLatencyP95();
        // p95 of [1..100] = value at index 95 of sorted array (0-based) = 96
        assertTrue(p95 >= 90 && p95 <= 100,
                "p95 of 1..100 ms must be in [90, 100], got " + p95);
    }

    @Test
    void recordLatency_reviewsAndBranches_trackedIndependently() {
        for (int i = 0; i < 20; i++) {
            metrics.recordReviewsQueryLatency(50);
            metrics.recordBranchesQueryLatency(20);
        }
        assertEquals(50, metrics.getReviewsQueryP95());
        assertEquals(20, metrics.getBranchesQueryP95());
    }

    // --- pool stats (null pool) ---

    @Test
    void getPoolStats_nullPool_returnsMinusOne() {
        assertEquals(-1, metrics.getPoolActiveConnections());
        assertEquals(-1, metrics.getPoolWaitingThreads());
    }
}
