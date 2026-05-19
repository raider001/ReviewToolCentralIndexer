package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebhookDispatcher}.
 */
class WebhookDispatcherTest {

    @Test
    void routesToRegisteredHandler() throws Exception {
        WebhookRouterImpl router = mock(WebhookRouterImpl.class);
        when(router.dispatch(eq("push"), any(), any())).thenReturn(true);

        WebhookDispatcher dispatcher = new WebhookDispatcher(router);
        HttpExchange exchange = prepareExchange("/webhooks/push", "application/json");

        dispatcher.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(200), eq(-1L));
        verify(router).dispatch(eq("push"), any(), any());
    }

    @Test
    void returns404ForUnknownSuffix() throws Exception {
        WebhookRouterImpl router = mock(WebhookRouterImpl.class);
        when(router.dispatch(any(), any(), any())).thenReturn(false);

        WebhookDispatcher dispatcher = new WebhookDispatcher(router);
        HttpExchange exchange = prepareExchange("/webhooks/unknown", null);

        dispatcher.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(404), eq(-1L));
    }

    private HttpExchange prepareExchange(String path, String contentType) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI(path));
        Headers reqHeaders = new Headers();
        if (contentType != null) {
            reqHeaders.add("Content-Type", contentType);
        }
        when(exchange.getRequestHeaders()).thenReturn(reqHeaders);
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }
}

