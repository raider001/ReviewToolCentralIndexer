package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Routes {@code POST /webhooks/{suffix}} to the registered plugin handler.
 *
 * <p>Strips the {@code /webhooks/} prefix from the request URI path and passes the
 * remaining suffix, request headers, and body bytes to
 * {@link WebhookRouterImpl#dispatch}. Returns {@code 200 OK} when a handler is
 * registered for the suffix; {@code 404 Not Found} otherwise.
 */
public final class WebhookDispatcher implements HttpHandler {

    private static final String WEBHOOKS_PREFIX = "/webhooks/";

    private final WebhookRouterImpl router;

    /**
     * Constructs a {@code WebhookDispatcher} backed by the given router.
     *
     * @param router the webhook router populated by the provider plugin
     */
    public WebhookDispatcher(WebhookRouterImpl router) {
        this.router = router;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring(WEBHOOKS_PREFIX.length());
        Map<String, String> headers = collectHeaders(exchange);
        byte[] body = exchange.getRequestBody().readAllBytes();
        boolean dispatched = router.dispatch(suffix, headers, body);
        int status = dispatched ? 200 : 404;
        exchange.sendResponseHeaders(status, -1);
        exchange.getResponseBody().close();
    }

    private Map<String, String> collectHeaders(HttpExchange exchange) {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }
}

