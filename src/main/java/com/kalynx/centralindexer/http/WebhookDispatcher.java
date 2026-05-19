package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.exception.EventQueuedForRetryException;
import com.kalynx.centralindexer.exception.RetryQueueFullException;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Routes {@code POST /webhooks/{suffix}} to the registered plugin handler.
 *
 * <p>Strips the {@code /webhooks/} prefix from the request URI path and passes the
 * remaining suffix, request headers, and body bytes to
 * {@link WebhookRouterImpl#dispatch}. Response codes:
 * <ul>
 *   <li>{@code 200 OK} — handler registered and event persisted immediately.</li>
 *   <li>{@code 202 Accepted} — handler registered but DB was unavailable; event has
 *       been queued for retry ({@link EventQueuedForRetryException} caught).</li>
 *   <li>{@code 404 Not Found} — no handler registered for the suffix.</li>
 *   <li>{@code 503 Service Unavailable} — retry queue is full
 *       ({@link RetryQueueFullException} caught).</li>
 * </ul>
 */
public final class WebhookDispatcher implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
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

        String deliveryId = headers.getOrDefault("x-github-delivery",
                headers.getOrDefault("X-GitHub-Delivery", "-"));
        String eventType = headers.getOrDefault("x-github-event",
                headers.getOrDefault("X-GitHub-Event", "-"));
        log.info("Webhook received: provider='{}' event='{}' delivery='{}' bytes={}",
                suffix, eventType, deliveryId, body.length);

        try {
            boolean dispatched = router.dispatch(suffix, headers, body);
            int status = dispatched ? 200 : 404;
            if (!dispatched) {
                log.warn("No handler registered for webhook provider '{}'", suffix);
            }
            exchange.sendResponseHeaders(status, -1);
        } catch (EventQueuedForRetryException e) {
            log.info("Webhook delivery='{}' queued for retry (DB temporarily unavailable)", deliveryId);
            exchange.sendResponseHeaders(202, -1);
        } catch (RetryQueueFullException e) {
            log.warn("Webhook delivery='{}' dropped — retry queue is full", deliveryId);
            exchange.sendResponseHeaders(503, -1);
        } finally {
            exchange.getResponseBody().close();
        }
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
