package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /**
     * Regression test: com.sun.net.httpserver.Headers normalises header names to title-case
     * (e.g. "X-GitHub-Event" → "X-Github-Event"). The old collectHeaders() stored that key
     * as-is, so the handler's lowercase lookups ("x-github-event") always missed and every
     * GitHub push was silently dropped. The fix lowercases all keys in collectHeaders().
     */
    @Test
    @SuppressWarnings("unchecked")
    void headerNamesAreLowercasedBeforeDispatch() throws Exception {
        WebhookRouterImpl router = mock(WebhookRouterImpl.class);
        when(router.dispatch(any(), any(), any())).thenReturn(true);

        WebhookDispatcher dispatcher = new WebhookDispatcher(router);

        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI("/webhooks/github"));
        // GitHub sends "X-GitHub-Event"; com.sun.net.httpserver.Headers normalises it to
        // "X-Github-Event" (title-case each word). Either way, the dispatcher must lowercase it.
        Headers reqHeaders = new Headers();
        reqHeaders.add("X-GitHub-Event", "push");
        reqHeaders.add("X-GitHub-Delivery", "delivery-abc");
        reqHeaders.add("X-Hub-Signature-256", "sha256=deadbeef");
        when(exchange.getRequestHeaders()).thenReturn(reqHeaders);
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());

        dispatcher.handle(exchange);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(router).dispatch(eq("github"), captor.capture(), any());
        Map<String, String> headers = captor.getValue();
        assertTrue(headers.containsKey("x-github-event"),
                "x-github-event must be lowercase; was: " + headers.keySet());
        assertTrue(headers.containsKey("x-github-delivery"),
                "x-github-delivery must be lowercase; was: " + headers.keySet());
        assertTrue(headers.containsKey("x-hub-signature-256"),
                "x-hub-signature-256 must be lowercase; was: " + headers.keySet());
        assertEquals("push", headers.get("x-github-event"));
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

