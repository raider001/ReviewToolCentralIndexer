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
    void connectedClientsStartsAtZero() {
        assertEquals(0, metrics.getConnectedClients());
    }

    @Test
    void incrementAndDecrementConnectedClients() {
        metrics.incrementConnectedClients();
        metrics.incrementConnectedClients();
        assertEquals(2, metrics.getConnectedClients());

        metrics.decrementConnectedClients();
        assertEquals(1, metrics.getConnectedClients());
    }

    // --- SSE event rate ---

    @Test
    void writersPerSecondIsZeroWithNoEvents() {
        assertEquals(0.0, metrics.getSseWritersPerSecond(), 0.01);
    }

    @Test
    void writersPerSecondCountsRecentEvents() throws InterruptedException {
        metrics.recordSseEvent();
        metrics.recordSseEvent();
        metrics.recordSseEvent();

        double rate = metrics.getSseWritersPerSecond();
        assertEquals(3.0, rate, 0.01, "Three events recorded within 1s must count");
    }

    // --- latency percentiles ---

    @Test
    void p95ReturnsMinusOneWithInsufficientSamples() {
        // fewer than 20 samples → -1
        for (int i = 0; i < 19; i++) {
            metrics.recordSseWriteLatency(10);
        }
        assertEquals(-1, metrics.getSseWriteLatencyP95());
    }

    @Test
    void p95ComputedCorrectlyWith100Samples() {
        for (int i = 1; i <= 100; i++) {
            metrics.recordSseWriteLatency(i); // 1..100 ms
        }
        long p95 = metrics.getSseWriteLatencyP95();
        // p95 of [1..100] = value at index 95 of sorted array (0-based) = 96
        assertTrue(p95 >= 90 && p95 <= 100,
                "p95 of 1..100 ms must be in [90, 100], got " + p95);
    }

    @Test
    void reviewsAndBranchesLatencyTrackedIndependently() {
        for (int i = 0; i < 20; i++) {
            metrics.recordReviewsQueryLatency(50);
            metrics.recordBranchesQueryLatency(20);
        }
        assertEquals(50, metrics.getReviewsQueryP95());
        assertEquals(20, metrics.getBranchesQueryP95());
    }

    // --- backfill progress ---

    @Test
    void backfillProgressDefaultsToMinusOne() {
        assertEquals(-1.0, metrics.getBackfillProgress(), 0.001);
    }

    @Test
    void backfillProgressCanBeSet() {
        metrics.setBackfillProgress(42.5);
        assertEquals(42.5, metrics.getBackfillProgress(), 0.001);
    }

    // --- pool stats (null pool) ---

    @Test
    void poolStatsReturnMinusOneWhenPoolIsNull() {
        assertEquals(-1, metrics.getPoolActiveConnections());
        assertEquals(-1, metrics.getPoolWaitingThreads());
    }
}
