package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.config.AuthConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * {@link HttpHandler} decorator that enforces Bearer token authentication on
 * {@code /events} and {@code /events/*} paths.
 *
 * <p>Requests to {@code /webhooks/*} and {@code /health} are always passed through to the
 * downstream handler regardless of the {@code auth.enabled} flag. When auth is enabled and
 * the {@code Authorization} header is absent, the response is {@code 401 Unauthorized}. A
 * header that does not match the configured token yields {@code 403 Forbidden}. A matching
 * token delegates to the downstream handler normally.
 *
 * <p>When {@code auth.enabled} is {@code false}, all requests are forwarded unconditionally.
 */
public final class AuthFilter implements HttpHandler {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthConfig config;
    private final HttpHandler downstream;

    /**
     * Wraps {@code downstream} with auth enforcement driven by {@code config}.
     *
     * @param config     the auth configuration; must not be {@code null}
     * @param downstream the handler to delegate to when auth passes; must not be {@code null}
     */
    public AuthFilter(AuthConfig config, HttpHandler downstream) {
        this.config = config;
        this.downstream = downstream;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (requiresAuth(path)) {
            if (!config.isEnabled()) {
                downstream.handle(exchange);
                return;
            }
            String authHeader = exchange.getRequestHeaders().getFirst(AUTH_HEADER);
            if (authHeader == null) {
                sendPlainText(exchange, 401, "Unauthorized");
                return;
            }
            if (!authHeader.equals(BEARER_PREFIX + config.getBearerToken())) {
                sendPlainText(exchange, 403, "Forbidden");
                return;
            }
        }
        downstream.handle(exchange);
    }

    private boolean requiresAuth(String path) {
        return path.equals("/events") || path.startsWith("/events/");
    }

    private void sendPlainText(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }
}

