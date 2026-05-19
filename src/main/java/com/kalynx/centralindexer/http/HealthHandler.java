package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.db.ConnectionPool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Handles {@code GET /health}.
 *
 * <p>Checks database connectivity by executing {@code SELECT 1} on a pooled connection.
 * Returns {@code {"status":"UP","db":"UP"}} when the query succeeds, or
 * {@code {"status":"UP","db":"DOWN"}} when it throws {@link SQLException}. The HTTP
 * response status is always {@code 200 OK}.
 */
public final class HealthHandler implements HttpHandler {

    private final ConnectionPool pool;

    /**
     * Constructs a {@code HealthHandler} that checks the given connection pool.
     *
     * @param pool the pool to ping for the DB health indicator
     */
    public HealthHandler(ConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String dbStatus = pingDatabase() ? "UP" : "DOWN";
        String json = "{\"status\":\"UP\",\"db\":\"" + dbStatus + "\"}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private boolean pingDatabase() {
        try {
            Connection conn = pool.acquire();
            try {
                conn.createStatement().execute("SELECT 1");
                return true;
            } catch (SQLException e) {
                return false;
            } finally {
                pool.release(conn);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

