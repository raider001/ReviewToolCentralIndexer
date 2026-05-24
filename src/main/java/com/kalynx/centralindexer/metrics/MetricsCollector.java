package com.kalynx.centralindexer.metrics;

import com.kalynx.centralindexer.db.ConnectionPool;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central metrics store for the indexer. Collects latency samples and counters from
 * instrumented handlers and exposes them for the {@code GET /metrics} endpoint.
 *
 * <p>All recording methods are thread-safe. Percentile reads are approximate — they
 * copy a snapshot of the rolling window without locking.
 */
public final class MetricsCollector {

    private static final int WINDOW_SIZE = 1000;
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private static final long TWO_SECONDS_NANOS = 2 * ONE_SECOND_NANOS;

    private final ConnectionPool pool;

    private final AtomicLong connectedClients = new AtomicLong(0);

    private final ConcurrentLinkedDeque<Long> sseEventNanos = new ConcurrentLinkedDeque<>();

    private final long[] sseWriteLatencies    = new long[WINDOW_SIZE];
    private final AtomicInteger sseWriteIdx   = new AtomicInteger(0);
    private final long[] reviewsLatencies     = new long[WINDOW_SIZE];
    private final AtomicInteger reviewsIdx    = new AtomicInteger(0);
    private final long[] branchesLatencies    = new long[WINDOW_SIZE];
    private final AtomicInteger branchesIdx   = new AtomicInteger(0);

    private final AtomicReference<Double> backfillProgress = new AtomicReference<>(-1.0);

    public MetricsCollector(ConnectionPool pool) {
        this.pool = pool;
    }

    // --- SSE client tracking ---

    public void incrementConnectedClients() {
        connectedClients.incrementAndGet();
    }

    public void decrementConnectedClients() {
        connectedClients.decrementAndGet();
    }

    // --- SSE event / write latency ---

    public void recordSseWriteLatency(long millis) {
        sseWriteLatencies[sseWriteIdx.getAndIncrement() % WINDOW_SIZE] = millis;
    }

    /**
     * Records that one SSE event was written. Prunes entries older than 2 seconds to
     * bound memory usage.
     */
    public void recordSseEvent() {
        long now = System.nanoTime();
        sseEventNanos.addLast(now);
        Long oldest;
        while ((oldest = sseEventNanos.peekFirst()) != null && now - oldest > TWO_SECONDS_NANOS) {
            sseEventNanos.pollFirst();
        }
    }

    // --- handler latencies ---

    public void recordReviewsQueryLatency(long millis) {
        reviewsLatencies[reviewsIdx.getAndIncrement() % WINDOW_SIZE] = millis;
    }

    public void recordBranchesQueryLatency(long millis) {
        branchesLatencies[branchesIdx.getAndIncrement() % WINDOW_SIZE] = millis;
    }

    // --- backfill progress ---

    /**
     * Sets the current backfill progress percentage (0–100). Pass {@code -1} to indicate
     * no backfill is running.
     */
    public void setBackfillProgress(double pct) {
        backfillProgress.set(pct);
    }

    // --- read accessors ---

    public long getConnectedClients() {
        return connectedClients.get();
    }

    /**
     * Returns the number of SSE events written in the last second.
     */
    public double getSseWritersPerSecond() {
        long cutoff = System.nanoTime() - ONE_SECOND_NANOS;
        long count = 0;
        for (Long t : sseEventNanos) {
            if (t >= cutoff) count++;
        }
        return count;
    }

    public long getSseWriteLatencyP95() {
        return p95(sseWriteLatencies, sseWriteIdx.get());
    }

    public long getReviewsQueryP95() {
        return p95(reviewsLatencies, reviewsIdx.get());
    }

    public long getBranchesQueryP95() {
        return p95(branchesLatencies, branchesIdx.get());
    }

    public double getBackfillProgress() {
        return backfillProgress.get();
    }

    public int getPoolActiveConnections() {
        return pool != null ? pool.getActiveConnections() : -1;
    }

    public int getPoolWaitingThreads() {
        return pool != null ? pool.getWaitingThreads() : -1;
    }

    /**
     * Returns the p95 of the samples recorded so far in {@code window}, or {@code -1}
     * if fewer than 20 samples have been recorded.
     */
    private static long p95(long[] window, int totalWritten) {
        int count = Math.min(totalWritten, WINDOW_SIZE);
        if (count < 20) return -1;
        long[] snapshot = Arrays.copyOf(window, count);
        Arrays.sort(snapshot);
        return snapshot[(int) (count * 0.95)];
    }
}
