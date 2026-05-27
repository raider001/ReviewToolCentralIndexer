package com.kalynx.centralindexer.metrics;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class CpuMetrics {

    private final MetricsCollector.TimeSeriesBuffer cpuSamples = new MetricsCollector.TimeSeriesBuffer();
    private final List<MetricsCollector.TimeSeriesBuffer> perCoreSamples;
    private final ThreadMXBean threadMxBean = ManagementFactory.getThreadMXBean();
    private final ConcurrentHashMap<Long, Long> prevThreadCpuNs = new ConcurrentHashMap<>();
    private volatile long prevSampleNs = System.nanoTime();

    CpuMetrics() {
        int cores = Runtime.getRuntime().availableProcessors();
        List<MetricsCollector.TimeSeriesBuffer> bufs = new ArrayList<>(cores);
        for (int i = 0; i < cores; i++) bufs.add(new MetricsCollector.TimeSeriesBuffer());
        this.perCoreSamples = Collections.unmodifiableList(bufs);
    }

    void record() {
        cpuSamples.record(readPercent());
        double[] corePercents = sampleJvmPerCore();
        for (int i = 0; i < Math.min(corePercents.length, perCoreSamples.size()); i++) {
            perCoreSamples.get(i).record(corePercents[i]);
        }
    }

    MetricsCollector.TimeSeriesBuffer getSamples() { return cpuSamples; }

    List<MetricsCollector.TimeSeriesBuffer> getPerCoreSamples() { return perCoreSamples; }

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

    private static double readPercent() {
        java.lang.management.OperatingSystemMXBean os =
                ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            double load = sunOs.getProcessCpuLoad();
            return load < 0 ? 0.0 : load * 100.0;
        }
        double avg = os.getSystemLoadAverage();
        return avg < 0 ? 0.0 : Math.min(100.0, avg / os.getAvailableProcessors() * 100.0);
    }
}
