package com.kalynx.centralindexer.metrics;

import com.kalynx.centralindexer.db.ConnectionPool;

final class ConnectionMetrics {

    private final ConnectionPool pool;
    private final MetricsCollector.TimeSeriesBuffer samples = new MetricsCollector.TimeSeriesBuffer();

    ConnectionMetrics(ConnectionPool pool) {
        this.pool = pool;
    }

    void record() { samples.record(getActiveConnections()); }

    MetricsCollector.TimeSeriesBuffer getSamples() { return samples; }

    int getActiveConnections() { return pool != null ? pool.getActiveConnections() : -1; }
    int getWaitingThreads()    { return pool != null ? pool.getWaitingThreads()    : -1; }
}
