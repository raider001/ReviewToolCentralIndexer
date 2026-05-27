package com.kalynx.centralindexer.metrics;

import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.lifecycle.Lifecycle;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central metrics store for the indexer. Collects latency samples, counters, and
 * time-windowed resource snapshots from instrumented handlers and exposes them for
 * the {@code GET /metrics} endpoint and the optional GUI.
 *
 * <p>All recording methods are thread-safe. Construct one instance in {@code Main}
 * and pass it through the object graph — there is no static singleton.
 *
 * <p>Time-series data (CPU, memory, connections, provider API calls) is retained for
 * up to 24 hours and sampled every second from a background thread started by
 * {@link #start()}.
 */
public final class MetricsCollector implements Lifecycle {

    // --- time-series buffer ------------------------------------------------------

    /**
     * Lock-free, timestamped rolling buffer that retains samples for at most 24 hours.
     * Samples are appended to the tail and pruned from the head by the recording thread.
     */
    public static final class TimeSeriesBuffer {

        public record Sample(long timestampMs, double value) {}

        private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

        private final ConcurrentLinkedDeque<Sample> samples = new ConcurrentLinkedDeque<>();

        public void record(double value) {
            long now = System.currentTimeMillis();
            samples.addLast(new Sample(now, value));
            Sample head;
            while ((head = samples.peekFirst()) != null && now - head.timestampMs() > MAX_AGE_MS) {
                samples.pollFirst();
            }
        }

        /** Returns the average of all samples within the given window (ms), or 0 if none. */
        public double average(long windowMs) {
            long cutoff = System.currentTimeMillis() - windowMs;
            return samples.stream()
                    .filter(s -> s.timestampMs() >= cutoff)
                    .mapToDouble(Sample::value)
                    .average()
                    .orElse(0.0);
        }

        /** Returns the count of samples within the given window (ms). */
        public long count(long windowMs) {
            long cutoff = System.currentTimeMillis() - windowMs;
            return samples.stream()
                    .filter(s -> s.timestampMs() >= cutoff)
                    .count();
        }

        /** Returns a snapshot of all samples within the given window, oldest first. */
        public List<Sample> getWindow(long windowMs) {
            long cutoff = System.currentTimeMillis() - windowMs;
            return samples.stream()
                    .filter(s -> s.timestampMs() >= cutoff)
                    .toList();
        }
    }

    // --- constants ---------------------------------------------------------------

    private static final int WINDOW_SIZE = 1000;

    // --- fields ------------------------------------------------------------------

    private final MemoryMetrics memory;
    private final CpuMetrics cpu;
    private final ConnectionMetrics connection;
    private final SseEventMetrics sse;

    private final TimeSeriesBuffer apiCallSamples  = new TimeSeriesBuffer();
    private final long[] reviewsLatencies          = new long[WINDOW_SIZE];
    private final AtomicInteger reviewsIdx         = new AtomicInteger(0);
    private final long[] branchesLatencies         = new long[WINDOW_SIZE];
    private final AtomicInteger branchesIdx        = new AtomicInteger(0);

    private final ConcurrentHashMap<String, TimeSeriesBuffer> sseEventsByType  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TimeSeriesBuffer> webhooksByType   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TimeSeriesBuffer> restCallsByType  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger>   connectedClientIps = new ConcurrentHashMap<>();

    private volatile long diskFreeMb  = 0;
    private volatile long diskTotalMb = 0;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private ScheduledExecutorService sampler;

    // --- constructor -------------------------------------------------------------

    public MetricsCollector(ConnectionPool pool) {
        this.memory     = new MemoryMetrics();
        this.cpu        = new CpuMetrics();
        this.connection = new ConnectionMetrics(pool);
        this.sse        = new SseEventMetrics();
    }

    // --- lifecycle ---------------------------------------------------------------

    /** Starts the 1-second background sampler. Idempotent — safe to call more than once. */
    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-sampler");
            t.setDaemon(true);
            return t;
        });
        sampler.scheduleAtFixedRate(this::sample, 0, 1, TimeUnit.SECONDS);
    }

    /** Stops the background sampler. */
    @Override
    public void stop() {
        if (sampler != null) sampler.shutdownNow();
    }

    private void sample() {
        memory.record();
        cpu.record();
        connection.record();
        File f = new File(".").getAbsoluteFile();
        diskFreeMb  = f.getUsableSpace()  / (1024L * 1024L);
        diskTotalMb = f.getTotalSpace()   / (1024L * 1024L);
    }

    // --- provider API call tracking ----------------------------------------------

    /**
     * Records a single outbound HTTP call to an external provider API.
     * Each call is timestamped so windowed counts can be derived.
     */
    public void recordProviderApiCall() {
        apiCallSamples.record(1.0);
    }

    // --- SSE client tracking -----------------------------------------------------

    public void incrementConnectedClients(String clientIp) {
        sse.incrementClients();
        connectedClientIps.computeIfAbsent(clientIp, k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void decrementConnectedClients(String clientIp) {
        sse.decrementClients();
        AtomicInteger count = connectedClientIps.get(clientIp);
        if (count != null && count.decrementAndGet() <= 0) {
            connectedClientIps.remove(clientIp, count);
        }
    }

    // --- SSE event / write latency -----------------------------------------------

    public void recordSseWriteLatency(long millis) { sse.recordWriteLatency(millis); }

    public void recordSseEvent(String eventSseName) {
        sse.recordEvent();
        sseEventsByType.computeIfAbsent(eventSseName, k -> new TimeSeriesBuffer()).record(1.0);
    }

    public void incrementConnectedClients() { incrementConnectedClients("unknown"); }
    public void decrementConnectedClients() { decrementConnectedClients("unknown"); }

    // --- webhook tracking --------------------------------------------------------

    public void recordWebhookCall(String pathSuffix) {
        webhooksByType.computeIfAbsent(pathSuffix, k -> new TimeSeriesBuffer()).record(1.0);
    }

    // --- REST call tracking ------------------------------------------------------

    public void recordRestCall(String endpoint) {
        restCallsByType.computeIfAbsent(endpoint, k -> new TimeSeriesBuffer()).record(1.0);
    }

    // --- handler latencies -------------------------------------------------------

    public void recordReviewsQueryLatency(long millis) {
        reviewsLatencies[reviewsIdx.getAndIncrement() % WINDOW_SIZE] = millis;
    }

    public void recordBranchesQueryLatency(long millis) {
        branchesLatencies[branchesIdx.getAndIncrement() % WINDOW_SIZE] = millis;
    }

    // --- read accessors ----------------------------------------------------------

    public long   getConnectedClients()     { return sse.getConnectedClients(); }
    public double getSseWritersPerSecond()  { return sse.getWritersPerSecond(); }
    public long   getSseWriteLatencyP95()  { return sse.getWriteLatencyP95(); }
    public long   getReviewsQueryP95()      { return p95(reviewsLatencies,  reviewsIdx.get()); }
    public long   getBranchesQueryP95()     { return p95(branchesLatencies, branchesIdx.get()); }
    public int    getPoolActiveConnections(){ return connection.getActiveConnections(); }
    public int    getPoolWaitingThreads()   { return connection.getWaitingThreads(); }
    public long   getDiskFreeMb()           { return diskFreeMb; }
    public long   getDiskTotalMb()          { return diskTotalMb; }

    public Map<String, Long> getSseEventCountsByType(long windowMs) {
        Map<String, Long> result = new LinkedHashMap<>();
        sseEventsByType.forEach((type, buf) -> result.put(type, buf.count(windowMs)));
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Long> getWebhookCountsByType(long windowMs) {
        Map<String, Long> result = new LinkedHashMap<>();
        webhooksByType.forEach((type, buf) -> result.put(type, buf.count(windowMs)));
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Long> getRestCallCountsByType(long windowMs) {
        Map<String, Long> result = new LinkedHashMap<>();
        restCallsByType.forEach((type, buf) -> result.put(type, buf.count(windowMs)));
        return Collections.unmodifiableMap(result);
    }

    public Map<String, Integer> getConnectedClientIps() {
        Map<String, Integer> result = new LinkedHashMap<>();
        connectedClientIps.forEach((ip, count) -> {
            int n = count.get();
            if (n > 0) result.put(ip, n);
        });
        return Collections.unmodifiableMap(result);
    }

    // --- time-series accessors ---------------------------------------------------

    public TimeSeriesBuffer       getCpuSamples()        { return cpu.getSamples(); }
    public TimeSeriesBuffer       getMemorySamples()     { return memory.getSamples(); }
    public TimeSeriesBuffer       getConnectionSamples() { return connection.getSamples(); }
    public TimeSeriesBuffer       getApiCallSamples()    { return apiCallSamples; }
    public List<TimeSeriesBuffer> getPerCoreSamples()    { return cpu.getPerCoreSamples(); }

    /** Returns the JVM max-heap in MB, used as the memory chart Y-axis maximum. */
    public static double memoryMaxMb() { return MemoryMetrics.maxMb(); }

    // --- helpers -----------------------------------------------------------------

    private static long p95(long[] window, int totalWritten) {
        int count = Math.min(totalWritten, WINDOW_SIZE);
        if (count < 20) return -1;
        long[] snapshot = Arrays.copyOf(window, count);
        Arrays.sort(snapshot);
        return snapshot[(int) (count * 0.95)];
    }
}
