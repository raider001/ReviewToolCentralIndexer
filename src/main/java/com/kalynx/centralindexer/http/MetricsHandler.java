package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles {@code GET /metrics}.
 *
 * <p>Returns a JSON snapshot of current operational metrics sourced from
 * {@link MetricsCollector}. The response shape is:
 * <pre>{@code
 * {
 *   "sse": {
 *     "connected_clients_total": 42,
 *     "writers_per_second": 8.5,
 *     "write_latency_p95_ms": 12
 *   },
 *   "db": {
 *     "get_reviews_p95_ms": 45,
 *     "pool_active_connections": 3,
 *     "pool_waiting_threads": 0
 *   },
 *   "branches": {
 *     "typeahead_p95_ms": 18
 *   },
 *   "backfill": {
 *     "progress_pct": -1.0
 *   }
 * }
 * }</pre>
 *
 * <p>Latency values of {@code -1} indicate insufficient samples (fewer than 20 recorded).
 * {@code backfill.progress_pct} of {@code -1} indicates no backfill is currently running.
 */
public final class MetricsHandler implements HttpHandler {

    private final MetricsCollector metrics;

    public MetricsHandler(MetricsCollector metrics) {
        this.metrics = metrics;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            byte[] err = "{\"error\":\"method not allowed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(405, err.length);
            exchange.getResponseBody().write(err);
            exchange.getResponseBody().close();
            return;
        }

        String body = buildJson();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private String buildJson() {
        List<MetricsCollector.TimeSeriesBuffer> coreBufs = metrics.getPerCoreSamples();
        StringBuilder coreArr = new StringBuilder("[");
        for (int i = 0; i < coreBufs.size(); i++) {
            if (i > 0) coreArr.append(',');
            coreArr.append(String.format("%.2f", coreBufs.get(i).average(1000)));
        }
        coreArr.append(']');

        return "{"
                + "\"sse\":{"
                +   "\"connected_clients_total\":" + metrics.getConnectedClients() + ","
                +   "\"writers_per_second\":"       + String.format("%.2f", metrics.getSseWritersPerSecond()) + ","
                +   "\"write_latency_p95_ms\":"     + metrics.getSseWriteLatencyP95()
                + "},"
                + "\"db\":{"
                +   "\"get_reviews_p95_ms\":"       + metrics.getReviewsQueryP95() + ","
                +   "\"pool_active_connections\":"  + metrics.getPoolActiveConnections() + ","
                +   "\"pool_waiting_threads\":"     + metrics.getPoolWaitingThreads()
                + "},"
                + "\"branches\":{"
                +   "\"typeahead_p95_ms\":"         + metrics.getBranchesQueryP95()
                + "},"
                + "\"backfill\":{"
                +   "\"progress_pct\":"             + metrics.getBackfillProgress()
                + "},"
                + "\"system\":{"
                +   "\"cpu_percent\":"              + String.format("%.2f", metrics.getCpuSamples().average(1000)) + ","
                +   "\"memory_mb\":"                + String.format("%.2f", metrics.getMemorySamples().average(1000)) + ","
                +   "\"memory_max_mb\":"            + String.format("%.0f", MetricsCollector.memoryMaxMb()) + ","
                +   "\"active_connections\":"       + String.format("%.0f", metrics.getConnectionSamples().average(1000)) + ","
                +   "\"api_calls_last_second\":"    + metrics.getApiCallSamples().count(1000) + ","
                +   "\"per_core_cpu_percent\":"     + coreArr
                + "}"
                + "}";
    }
}
