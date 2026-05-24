package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link SseHandler}.
 */
class SseHandlerTest {

    @Test
    void missingRepositoryParamReturns400() throws Exception {
        SseHandler handler = new SseHandler(mock(PublisherRegistry.class));
        HttpExchange exchange = buildExchange("/events/stream", null);
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void sseResponseHeadersSet() throws Exception {
        Headers responseHeaders = new Headers();
        SseHandler handler = new SseHandler(immediatelyCompletingRegistry());
        HttpExchange exchange = buildExchangeWithResponseHeaders(
                "/events/stream", "repository=myrepo", responseHeaders);
        handler.handle(exchange);
        assertEquals("text/event-stream", responseHeaders.getFirst("Content-Type"));
        assertEquals("no-cache", responseHeaders.getFirst("Cache-Control"));
        assertEquals("no", responseHeaders.getFirst("X-Accel-Buffering"));
    }

    @Test
    void sseFrameFormatIsCorrect() throws Exception {
        ReviewEvent event = new ReviewEvent(42L, Instant.parse("2026-05-19T10:00:00Z"),
                "myrepo", EventType.REVIEW_CREATED, "rev-1", "alice", null, Map.of());
        PublisherRegistry registry = mock(PublisherRegistry.class);
        doAnswer(invocation -> {
            Flow.Subscriber<ReviewEvent> sub = invocation.getArgument(1);
            sub.onSubscribe(mock(Flow.Subscription.class));
            sub.onNext(event);
            sub.onComplete();
            return null;
        }).when(registry).subscribe(anyString(), any());

        SseHandler handler = new SseHandler(registry);
        HttpExchange exchange = buildExchange("/events/stream", "repository=myrepo");
        handler.handle(exchange);

        String body = capturedBody(exchange);
        assertTrue(body.contains("event: REVIEW_CREATED\n"), "Frame must include event line");
        assertTrue(body.contains("data: "), "Frame must include data line");
        assertTrue(body.contains("\n\n"), "Frame must end with blank line");
    }

    @Test
    void wildcardRepositoryReturns200WithCorrectHeaders() throws Exception {
        Headers responseHeaders = new Headers();
        SseHandler handler = new SseHandler(immediatelyCompletingRegistryForWildcard());
        HttpExchange exchange = buildExchangeWithResponseHeaders(
                "/events/stream", "repository=*", responseHeaders);
        handler.handle(exchange);
        assertEquals("text/event-stream", responseHeaders.getFirst("Content-Type"));
        assertEquals("no-cache", responseHeaders.getFirst("Cache-Control"));
        assertEquals("no", responseHeaders.getFirst("X-Accel-Buffering"));
    }

    private PublisherRegistry immediatelyCompletingRegistry() {
        PublisherRegistry registry = mock(PublisherRegistry.class);
        doAnswer(invocation -> {
            Flow.Subscriber<ReviewEvent> sub = invocation.getArgument(1);
            sub.onSubscribe(mock(Flow.Subscription.class));
            sub.onComplete();
            return null;
        }).when(registry).subscribe(anyString(), any());
        return registry;
    }

    private PublisherRegistry immediatelyCompletingRegistryForWildcard() {
        PublisherRegistry registry = mock(PublisherRegistry.class);
        doAnswer(invocation -> {
            Flow.Subscriber<ReviewEvent> sub = invocation.getArgument(0);
            sub.onSubscribe(mock(Flow.Subscription.class));
            sub.onComplete();
            return null;
        }).when(registry).subscribeAll(any());
        return registry;
    }

    private HttpExchange buildExchange(String path, String query) throws Exception {
        Headers responseHeaders = new Headers();
        return buildExchangeWithResponseHeaders(path, query, responseHeaders);
    }

    private HttpExchange buildExchangeWithResponseHeaders(String path, String query,
                                                          Headers responseHeaders) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        URI uri = query != null ? new URI(path + "?" + query) : new URI(path);
        org.mockito.Mockito.when(exchange.getRequestURI()).thenReturn(uri);
        org.mockito.Mockito.when(exchange.getResponseHeaders()).thenReturn(responseHeaders);
        org.mockito.Mockito.when(exchange.getResponseBody()).thenReturn(new ByteArrayOutputStream());
        return exchange;
    }

    private String capturedBody(HttpExchange exchange) {
        return ((ByteArrayOutputStream) exchange.getResponseBody()).toString(StandardCharsets.UTF_8);
    }
}
