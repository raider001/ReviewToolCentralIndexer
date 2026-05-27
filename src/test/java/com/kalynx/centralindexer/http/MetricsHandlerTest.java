package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MetricsHandler}.
 */
class MetricsHandlerTest {

    private MetricsHandler handler;

    @BeforeEach
    void setUp() {
        handler = new MetricsHandler(new MetricsCollector(null));
    }

    @Test
    void handle_getRequest_returns200() throws Exception {
        HttpExchange exchange = buildExchange("GET");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    void handle_getRequest_responseContainsTopLevelKeys() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("GET", body);
        handler.handle(exchange);

        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"sse\""),       "must contain sse key");
        assertTrue(json.contains("\"db\""),        "must contain db key");
        assertTrue(json.contains("\"branches\""),  "must contain branches key");
        assertTrue(json.contains("\"system\""),    "must contain system key");
    }

    @Test
    void handle_getRequest_responseContainsSseSubKeys() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("GET", body);
        handler.handle(exchange);

        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"connected_clients_total\""));
        assertTrue(json.contains("\"writers_per_second\""));
        assertTrue(json.contains("\"write_latency_p95_ms\""));
    }

    @Test
    void handle_getRequest_responseContainsDbSubKeys() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("GET", body);
        handler.handle(exchange);

        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"get_reviews_p95_ms\""));
        assertTrue(json.contains("\"pool_active_connections\""));
        assertTrue(json.contains("\"pool_waiting_threads\""));
    }

    @Test
    void handle_nullPool_poolStatsAreMinusOne() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("GET", body);
        handler.handle(exchange);

        String json = body.toString(StandardCharsets.UTF_8);
        // -1 must appear for both pool stats when pool is null
        assertTrue(json.contains("\"pool_active_connections\":-1"));
        assertTrue(json.contains("\"pool_waiting_threads\":-1"));
    }

    @Test
    void handle_postRequest_returns405() throws Exception {
        HttpExchange exchange = buildExchange("POST");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(405), anyLong());
    }

    @Test
    void handle_putRequest_returns405() throws Exception {
        HttpExchange exchange = buildExchange("PUT");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(405), anyLong());
    }

    // --- helpers ---

    private HttpExchange buildExchange(String method) throws Exception {
        return buildExchangeWithBody(method, new ByteArrayOutputStream());
    }

    private HttpExchange buildExchangeWithBody(String method, ByteArrayOutputStream body) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(body);
        return exchange;
    }
}
