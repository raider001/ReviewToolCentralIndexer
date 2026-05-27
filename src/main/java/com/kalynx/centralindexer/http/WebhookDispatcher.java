package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Routes {@code POST /webhooks/{suffix}} to the registered plugin handler.
 *
 * <p>Strips the {@code /webhooks/} prefix from the request URI path and passes the
 * remaining suffix, request headers, and body bytes to
 * {@link WebhookRouterImpl#dispatch}. Response codes:
 * <ul>
 *   <li>{@code 200 OK} — handler registered and event processed.</li>
 *   <li>{@code 404 Not Found} — no handler registered for the suffix.</li>
 * </ul>
 */
public final class WebhookDispatcher implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final String WEBHOOKS_PREFIX = "/webhooks/";

    private final WebhookRouterImpl router;
    private final MetricsCollector  metrics;

    public WebhookDispatcher(WebhookRouterImpl router) {
        this(router, null);
    }

    public WebhookDispatcher(WebhookRouterImpl router, MetricsCollector metrics) {
        this.router  = router;
        this.metrics = metrics;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String suffix = path.substring(WEBHOOKS_PREFIX.length());
        Map<String, String> headers = collectHeaders(exchange);
        byte[] body = exchange.getRequestBody().readAllBytes();

        String deliveryId = headers.getOrDefault("x-github-delivery", "-");
        String eventType = headers.getOrDefault("x-github-event", "-");
        log.info("Webhook received: provider='{}' event='{}' delivery='{}' bytes={}",
                suffix, eventType, deliveryId, body.length);

        int status = 500;
        try {
            boolean dispatched = router.dispatch(suffix, headers, body);
            status = dispatched ? 200 : 404;
            if (dispatched && metrics != null) metrics.recordWebhookCall(suffix);
            if (!dispatched) {
                log.warn("No handler registered for webhook provider '{}'", suffix);
            }
        } catch (Exception e) {
            log.error("Webhook handler threw unexpected exception: provider='{}' delivery='{}': {}",
                    suffix, deliveryId, e.getMessage(), e);
        } finally {
            try {
                exchange.sendResponseHeaders(status, -1);
            } catch (IOException ignored) {
            }
            exchange.getResponseBody().close();
        }
    }

    private Map<String, String> collectHeaders(HttpExchange exchange) {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name.toLowerCase(Locale.ROOT), values.get(0));
            }
        });
        return headers;
    }
}
