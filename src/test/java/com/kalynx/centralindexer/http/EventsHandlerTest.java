package com.kalynx.centralindexer.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EventsHandler}.
 */
class EventsHandlerTest {

    @Test
    void returnsPagedEventsWithCorrectNextSince() throws Exception {
        ReviewEvent event = testEvent(7L);
        EventRepository repo = mock(EventRepository.class);
        when(repo.hasEventAt(eq("myrepo"), eq(3L))).thenReturn(true);
        when(repo.queryEvents(eq("myrepo"), eq(3L), anyInt())).thenReturn(List.of(event));

        EventsHandler handler = new EventsHandler(repo);
        HttpExchange exchange = buildExchange("/events", "repository=myrepo&since=3");
        handler.handle(exchange);

        JsonObject response = parseResponse(exchange);
        assertEquals(1, response.getAsJsonArray("events").size());
        assertEquals(7L, response.get("nextSince").getAsLong());
    }

    @Test
    void emptyEventsNextSinceEqualsInputSince() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        when(repo.hasEventAt(eq("myrepo"), eq(5L))).thenReturn(true);
        when(repo.queryEvents(eq("myrepo"), eq(5L), anyInt())).thenReturn(Collections.emptyList());

        EventsHandler handler = new EventsHandler(repo);
        HttpExchange exchange = buildExchange("/events", "repository=myrepo&since=5");
        handler.handle(exchange);

        JsonObject response = parseResponse(exchange);
        assertEquals(0, response.getAsJsonArray("events").size());
        assertEquals(5L, response.get("nextSince").getAsLong());
    }

    @Test
    void defaultLimitIs100() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        when(repo.queryEvents(anyString(), anyLong(), anyInt())).thenReturn(Collections.emptyList());

        EventsHandler handler = new EventsHandler(repo);
        HttpExchange exchange = buildExchange("/events", "repository=myrepo&since=0");
        handler.handle(exchange);

        verify(repo).queryEvents(eq("myrepo"), eq(0L), eq(100));
    }

    @Test
    void missingRepositoryReturns400() throws Exception {
        EventsHandler handler = new EventsHandler(mock(EventRepository.class));
        HttpExchange exchange = buildExchange("/events", null);
        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void since0NeverCalls410Check() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        when(repo.queryEvents(anyString(), eq(0L), anyInt())).thenReturn(Collections.emptyList());

        EventsHandler handler = new EventsHandler(repo);
        HttpExchange exchange = buildExchange("/events", "repository=myrepo&since=0");
        handler.handle(exchange);

        verify(repo, never()).hasEventAt(anyString(), anyLong());
    }

    @Test
    void prunedCursorReturns410() throws Exception {
        EventRepository repo = mock(EventRepository.class);
        when(repo.hasEventAt(eq("myrepo"), eq(2L))).thenReturn(false);

        EventsHandler handler = new EventsHandler(repo);
        HttpExchange exchange = buildExchange("/events", "repository=myrepo&since=2");
        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(410), anyLong());
    }

    @Test
    void nullFieldsSerializedAsJsonNull() throws Exception {
        ReviewEvent event = new ReviewEvent(1L, Instant.parse("2026-05-19T10:00:00Z"),
                "myrepo", EventType.REVIEW_CREATED, null, null, null, Map.of());
        EventRepository repo = mock(EventRepository.class);
        when(repo.queryEvents(eq("myrepo"), eq(0L), anyInt())).thenReturn(List.of(event));

        EventsHandler handler = new EventsHandler(repo);
        HttpExchange exchange = buildExchange("/events", "repository=myrepo&since=0");
        handler.handle(exchange);

        String body = capturedBody(exchange);
        assertTrue(body.contains("\"reviewId\":null"), "reviewId must be serialised as null, not omitted");
        assertTrue(body.contains("\"actorUser\":null"), "actorUser must be serialised as null, not omitted");
        assertTrue(body.contains("\"deliveryId\":null"), "deliveryId must be serialised as null, not omitted");
        assertFalse(body.contains("\"reviewId\":{}"), "reviewId must not be an empty object");
    }

    private ReviewEvent testEvent(long seqNo) {
        return new ReviewEvent(seqNo, Instant.parse("2026-05-19T10:00:00Z"),
                "myrepo", EventType.REVIEW_UPDATED, "rev-1", "alice", "d-1", Map.of());
    }

    private HttpExchange buildExchange(String path, String query) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        URI uri = query != null ? new URI(path + "?" + query) : new URI(path);
        when(exchange.getRequestURI()).thenReturn(uri);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(out);
        return exchange;
    }

    private JsonObject parseResponse(HttpExchange exchange) {
        String body = capturedBody(exchange);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private String capturedBody(HttpExchange exchange) {
        return ((ByteArrayOutputStream) exchange.getResponseBody()).toString(StandardCharsets.UTF_8);
    }
}

