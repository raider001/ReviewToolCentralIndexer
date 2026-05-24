package com.kalynx.centralindexer.metrics;

import com.kalynx.centralindexer.db.ConnectionPool;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central metrics store for the indexer. Collects latency samples, counters, and
 * time-windowed resource snapshots from instrumented handlers and exposes them for
 * the {@code GET /metrics} endpoint and the optional GUI.
 *
 * <p>All recording methods are thread-safe. A singleton instance is initialised by
 * {@link #initialize(ConnectionPool)} at startup and accessed via {@link #getInstance()}.
 *
 * <p>Time-series data (CPU, memory, connections, provider API calls) is retained for
 * up to 24 hours and sampled every second from a background thread started by
 * {@link #start()}.
 */
public final class MetricsCollector {

    // --- singleton ---------------------------------------------------------------

    private static volatile MetricsCollector instance;

    /**
     * Initialises the singleton instance with the given connection pool.
     * Must be called exactly once before {@link #getInstance()}.
     */
    public static MetricsCollector initialize(ConnectionPool pool) {
        MetricsCollector m = new MetricsCollector(pool);
        instance = m;
        return m;
    }

    /**
     * Returns the singleton instance, or {@code null} if {@link #initialize} has not
     * been called yet.
     */
    public static MetricsCollector getInstance() {
        return instance;
    }

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
    private static final long ONE_SECOND_NANOS = 1_000_000_000L;
    private static final long TWO_SECONDS_NANOS = 2 * ONE_SECOND_NANOS;

    // --- fields ------------------------------------------------------------------

    private final ConnectionPool pool;

    // legacy per-request latency rings
    private final AtomicLong connectedClients = new AtomicLong(0);
    private final ConcurrentLinkedDeque<Long> sseEventNanos = new ConcurrentLinkedDeque<>();
    private final long[] sseWriteLatencies  = new long[WINDOW_SIZE];
    private final AtomicInteger sseWriteIdx = new AtomicInteger(0);
    private final long[] reviewsLatencies   = new long[WINDOW_SIZE];
    private final AtomicInteger reviewsIdx  = new AtomicInteger(0);
    private final long[] branchesLatencies  = new long[WINDOW_SIZE];
    private final AtomicInteger branchesIdx = new AtomicInteger(0);
    private final AtomicReference<Double> backfillProgress = new AtomicReference<>(-1.0);

    // time-series buffers (sampled every 1 s; one sample per provider HTTP call)
    private final TimeSeriesBuffer cpuSamples         = new TimeSeriesBuffer();
    private final TimeSeriesBuffer memorySamples      = new TimeSeriesBuffer();
    private final TimeSeriesBuffer connectionSamples  = new TimeSeriesBuffer();
    private final TimeSeriesBuffer apiCallSamples     = new TimeSeriesBuffer();

    // per-logical-core CPU buffers (one per core, same 24h window)
    private final List<TimeSeriesBuffer> perCoreSamples;

    // ThreadMXBean state for JVM-only per-core CPU sampling
    private final ThreadMXBean                threadMxBean = ManagementFactory.getThreadMXBean();
    private final ConcurrentHashMap<Long,Long> prevThreadCpuNs = new ConcurrentHashMap<>();
    private volatile long                      prevSampleNs    = System.nanoTime();

    private ScheduledExecutorService sampler;

    // --- constructor -------------------------------------------------------------

    public MetricsCollector(ConnectionPool pool) {
        this.pool = pool;
        int cores = Runtime.getRuntime().availableProcessors();
        List<TimeSeriesBuffer> bufs = new ArrayList<>(cores);
        for (int i = 0; i < cores; i++) bufs.add(new TimeSeriesBuffer());
        this.perCoreSamples = Collections.unmodifiableList(bufs);
    }

    // --- lifecycle ---------------------------------------------------------------

    /** Starts the 1-second background sampler for CPU, memory, connections, and per-core CPU. */
    public void start() {
        sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-sampler");
            t.setDaemon(true);
            return t;
        });
        sampler.scheduleAtFixedRate(this::sample, 0, 1, TimeUnit.SECONDS);
    }

    /** Stops the background sampler. */
    public void stop() {
        if (sampler != null) sampler.shutdownNow();
    }

    private void sample() {
        cpuSamples.record(readCpuPercent());
        memorySamples.record(readMemoryMb());
        connectionSamples.record(getPoolActiveConnections());

        double[] corePercents = sampleJvmPerCore();
        for (int i = 0; i < Math.min(corePercents.length, perCoreSamples.size()); i++) {
            perCoreSamples.get(i).record(corePercents[i]);
        }
    }

    /**
     * Distributes the JVM's own thread CPU usage across N virtual cores using
     * ThreadMXBean deltas. Each thread's CPU share (as % of one core) is bin-packed
     * into the least-loaded virtual core, giving a meaningful per-core view of JVM load.
     */
    private double[] sampleJvmPerCore() {
        int cores = perCoreSamples.size();
        long[] ids = threadMxBean.getAllThreadIds();
        long now       = System.nanoTime();
        long elapsedNs = now - prevSampleNs;
        prevSampleNs   = now;
        if (elapsedNs <= 0) return new double[cores];

        double[] threadPct = new double[ids.length];
        Set<Long> live = new HashSet<>(ids.length * 2);
        for (int i = 0; i < ids.length; i++) {
            long id    = ids[i];
            live.add(id);
            long cpuNs = threadMxBean.getThreadCpuTime(id);
            if (cpuNs < 0) continue;
            Long prev = prevThreadCpuNs.put(id, cpuNs);
            if (prev != null) {
                threadPct[i] = Math.max(0, Math.min(100.0, (double)(cpuNs - prev) / elapsedNs * 100.0));
            }
        }
        prevThreadCpuNs.keySet().retainAll(live);

        // Sort descending so largest threads are bin-packed first
        Arrays.sort(threadPct);
        for (int lo = 0, hi = threadPct.length - 1; lo < hi; lo++, hi--) {
            double tmp = threadPct[lo]; threadPct[lo] = threadPct[hi]; threadPct[hi] = tmp;
        }

        double[] coreLoad = new double[cores];
        for (double pct : threadPct) {
            if (pct <= 0) break;
            int min = 0;
            for (int c = 1; c < cores; c++) {
                if (coreLoad[c] < coreLoad[min]) min = c;
            }
            coreLoad[min] = Math.min(100.0, coreLoad[min] + pct);
        }
        return coreLoad;
    }

    // --- provider API call tracking ----------------------------------------------

    /**
     * Records a single outbound HTTP call to an external provider API (GitHub, GitLab,
     * Bitbucket). Each call is timestamped so windowed counts can be derived.
     */
    public void recordProviderApiCall() {
        apiCallSamples.record(1.0);
    }

    // --- SSE client tracking -----------------------------------------------------

    public void incrementConnectedClients() { connectedClients.incrementAndGet(); }
    public void decrementConnectedClients() { connectedClients.decrementAndGet(); }

    // --- SSE event / write latency -----------------------------------------------

    public void recordSseWriteLatency(long millis) {
        sseWriteLatencies[sseWriteIdx.getAndIncrement() % WINDOW_SIZE] = millis;
    }

    public void recordSseEvent() {
        long now = System.nanoTime();
        sseEventNanos.addLast(now);
        Long oldest;
        while ((oldest = sseEventNanos.peekFirst()) != null && now - oldest > TWO_SECONDS_NANOS) {
            sseEventNanos.pollFirst();
        }
    }

    // --- handler latencies -------------------------------------------------------

    public void recordReviewsQueryLatency(long millis) {
        reviewsLatencies[reviewsIdx.getAndIncrement() % WINDOW_SIZE] = millis;
    }

    public void recordBranchesQueryLatency(long millis) {
        branchesLatencies[branchesIdx.getAndIncrement() % WINDOW_SIZE] = millis;
    }

    // --- backfill progress -------------------------------------------------------

    public void setBackfillProgress(double pct) { backfillProgress.set(pct); }

    // --- read accessors (legacy) -------------------------------------------------

    public long getConnectedClients() { return connectedClients.get(); }

    public double getSseWritersPerSecond() {
        long cutoff = System.nanoTime() - ONE_SECOND_NANOS;
        long count = 0;
        for (Long t : sseEventNanos) {
            if (t >= cutoff) count++;
        }
        return count;
    }

    public long getSseWriteLatencyP95()  { return p95(sseWriteLatencies, sseWriteIdx.get()); }
    public long getReviewsQueryP95()     { return p95(reviewsLatencies,   reviewsIdx.get()); }
    public long getBranchesQueryP95()    { return p95(branchesLatencies,  branchesIdx.get()); }
    public double getBackfillProgress()  { return backfillProgress.get(); }

    public int getPoolActiveConnections() { return pool != null ? pool.getActiveConnections() : -1; }
    public int getPoolWaitingThreads()    { return pool != null ? pool.getWaitingThreads()    : -1; }

    // --- time-series accessors (GUI) ---------------------------------------------

    public TimeSeriesBuffer getCpuSamples()        { return cpuSamples; }
    public TimeSeriesBuffer getMemorySamples()     { return memorySamples; }
    public TimeSeriesBuffer getConnectionSamples() { return connectionSamples; }
    public TimeSeriesBuffer getApiCallSamples()    { return apiCallSamples; }

    // --- system stats ------------------------------------------------------------

    /** Returns the per-core CPU sample buffers (one per logical core, oldest-first). */
    public List<TimeSeriesBuffer> getPerCoreSamples() { return perCoreSamples; }

    /** Returns the JVM max-heap in MB, used as the memory chart Y-axis maximum. */
    public static double memoryMaxMb() {
        return Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0);
    }

    private static double readCpuPercent() {
        java.lang.management.OperatingSystemMXBean os =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double load = sunOs.getProcessCpuLoad();
            // [0,1] fraction of all CPUs combined → multiply by 100 for %.
            return load < 0 ? 0.0 : load * 100.0;
        }
        double avg = os.getSystemLoadAverage();
        return avg < 0 ? 0.0 : Math.min(100.0, avg / os.getAvailableProcessors() * 100.0);
    }

    private static double readMemoryMb() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return used / (1024.0 * 1024.0);
    }

    // --- helpers -----------------------------------------------------------------

    private static long p95(long[] window, int totalWritten) {
        int count = Math.min(totalWritten, WINDOW_SIZE);
        if (count < 20) return -1;
        long[] snapshot = Arrays.copyOf(window, count);
        Arrays.sort(snapshot);
        return snapshot[(int) (count * 0.95)];
    }
}