package com.kalynx.centralindexer.metrics;

import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class SseEventMetrics {

    private static final int WINDOW_SIZE = 1000;
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private static final long TWO_SECONDS_NANOS = 2 * ONE_SECOND_NANOS;

    private final AtomicLong connectedClients = new AtomicLong(0);
    private final ConcurrentLinkedDeque<Long> eventNanos = new ConcurrentLinkedDeque<>();
    private final long[] writeLatencies = new long[WINDOW_SIZE];
    private final AtomicInteger writeIdx = new AtomicInteger(0);

    void incrementClients() { connectedClients.incrementAndGet(); }
    void decrementClients() { connectedClients.decrementAndGet(); }

    void recordWriteLatency(long millis) {
        writeLatencies[writeIdx.getAndIncrement() % WINDOW_SIZE] = millis;
    }

    void recordEvent() {
        long now = System.nanoTime();
        eventNanos.addLast(now);
        Long oldest;
        while ((oldest = eventNanos.peekFirst()) != null && now - oldest > TWO_SECONDS_NANOS) {
            eventNanos.pollFirst();
        }
    }

    long getConnectedClients() { return connectedClients.get(); }

    double getWritersPerSecond() {
        long cutoff = System.nanoTime() - ONE_SECOND_NANOS;
        long count = 0;
        for (Long t : eventNanos) {
            if (t >= cutoff) count++;
        }
        return count;
    }

    long getWriteLatencyP95() { return p95(writeLatencies, writeIdx.get()); }

    private static long p95(long[] window, int totalWritten) {
        int count = Math.min(totalWritten, WINDOW_SIZE);
        if (count < 20) return -1;
        long[] snapshot = Arrays.copyOf(window, count);
        Arrays.sort(snapshot);
        return snapshot[(int) (count * 0.95)];
    }
}
