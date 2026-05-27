package com.kalynx.centralindexer.metrics;

final class MemoryMetrics {

    private final MetricsCollector.TimeSeriesBuffer samples = new MetricsCollector.TimeSeriesBuffer();

    void record() { samples.record(readMb()); }

    MetricsCollector.TimeSeriesBuffer getSamples() { return samples; }

    static double maxMb() {
        return Runtime.getRuntime().maxMemory() / (1024.0 * 1024.0);
    }

    private static double readMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024.0 * 1024.0);
    }
}
