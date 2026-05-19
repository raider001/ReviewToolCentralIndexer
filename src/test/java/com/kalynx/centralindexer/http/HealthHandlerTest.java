package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.db.ConnectionPool;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HealthHandler}.
 */
class HealthHandlerTest {

    @Test
    void upResponseWhenDbPingSucceeds() throws Exception {
        ConnectionPool pool = mockPool(false);
        HealthHandler handler = new HealthHandler(pool);
        HttpExchange exchange = prepareExchange();

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        String body = capturedBody(exchange);
        assertTrue(body.contains("\"status\":\"UP\""));
        assertTrue(body.contains("\"db\":\"UP\""));
    }

    @Test
    void dbDownWhenPingThrowsSqlException() throws Exception {
        ConnectionPool pool = mockPool(true);
        HealthHandler handler = new HealthHandler(pool);
        HttpExchange exchange = prepareExchange();

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        String body = capturedBody(exchange);
        assertTrue(body.contains("\"db\":\"DOWN\""));
    }

    private ConnectionPool mockPool(boolean throwOnPing) throws Exception {
        ConnectionPool pool = mock(ConnectionPool.class);
        Connection conn = mock(Connection.class);
        when(pool.acquire()).thenReturn(conn);
        if (throwOnPing) {
            when(conn.createStatement()).thenThrow(new SQLException("connection refused"));
        } else {
            Statement stmt = mock(Statement.class);
            when(conn.createStatement()).thenReturn(stmt);
        }
        return pool;
    }

    private HttpExchange prepareExchange() throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(body);
        return exchange;
    }

    private String capturedBody(HttpExchange exchange) {
        return ((ByteArrayOutputStream) exchange.getResponseBody())
                .toString(StandardCharsets.UTF_8);
    }
}

