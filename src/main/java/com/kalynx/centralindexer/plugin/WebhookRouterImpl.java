package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.spi.WebhookHandler;
import com.kalynx.centralindexer.spi.WebhookRouter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core implementation of {@link WebhookRouter}.
 *
 * <p>Handlers are registered by the provider plugin via {@link #registerPost} and
 * dispatched by the HTTP server layer via {@link #dispatch}.
 *
 * <p>This class is thread-safe: the handler map is a {@link ConcurrentHashMap}, so
 * registrations and dispatches may occur concurrently.
 */
public final class WebhookRouterImpl implements WebhookRouter {

    private final Map<String, WebhookHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Registers a handler for {@code POST /webhooks/{pathSuffix}}.
     *
     * @param pathSuffix path segment after {@code /webhooks/}; must not be {@code null} or blank
     * @param handler    the handler to invoke for matching requests; must not be {@code null}
     */
    @Override
    public void registerPost(String pathSuffix, WebhookHandler handler) {
        handlers.put(pathSuffix, handler);
    }

    /**
     * Dispatches an inbound webhook request to the registered handler for the given suffix.
     *
     * @param pathSuffix the path suffix extracted from the request URI
     * @param headers    HTTP request headers
     * @param body       raw request body bytes
     * @return {@code true} if a handler was found and invoked; {@code false} for an
     *         unrecognised suffix
     */
    public boolean dispatch(String pathSuffix, Map<String, String> headers, byte[] body) {
        WebhookHandler handler = handlers.get(pathSuffix);
        if (handler == null) {
            return false;
        }
        handler.handle(headers, body);
        return true;
    }
}

