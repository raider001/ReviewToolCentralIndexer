package com.kalynx.centralindexer.http;

import com.google.gson.Gson;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles {@code GET /events?repository=&lt;repo&gt;&amp;since=&lt;n&gt;&amp;limit=&lt;l&gt;}.
 *
 * <p>Returns a JSON object {@code {"events":[...],"nextSince":&lt;n&gt;}} where
 * {@code nextSince} is the {@code sequenceNo} of the last returned event, or the
 * input {@code since} value when the event array is empty.
 *
 * <p>Error responses:
 * <ul>
 *   <li>{@code 400 Bad Request} — {@code repository} parameter is absent.</li>
 *   <li>{@code 410 Gone} — {@code since &gt; 0} and {@link EventRepository#hasEventAt}
 *       returns {@code false} (the cursor has fallen outside the retention window).</li>
 *   <li>{@code 503 Service Unavailable} — the repository DAO was not provided (only
 *       occurs when the server is constructed without an {@link EventRepository}).</li>
 * </ul>
 */
public final class EventsHandler implements HttpHandler {

    private static final int DEFAULT_LIMIT = 100;
    private static final String PARAM_REPOSITORY = "repository";
    private static final String PARAM_SINCE = "since";
    private static final String PARAM_LIMIT = "limit";

    private final EventRepository eventRepository;
    private final Gson gson;

    /**
     * Constructs an {@code EventsHandler} backed by the given repository.
     *
     * @param eventRepository the event repository, or {@code null} when unavailable
     */
    public EventsHandler(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
        this.gson = GsonFactory.getInstance();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (eventRepository == null) {
            sendError(exchange, 503, "{\"error\":\"Service unavailable\"}");
            return;
        }

        String repo = getParam(exchange, PARAM_REPOSITORY);
        if (repo == null) {
            sendError(exchange, 400, "{\"error\":\"repository parameter is required\"}");
            return;
        }

        long since = parseLongParam(exchange, PARAM_SINCE, 0L);
        int limit = parseIntParam(exchange, PARAM_LIMIT, DEFAULT_LIMIT);

        if (since > 0) {
            try {
                if (!eventRepository.hasEventAt(repo, since)) {
                    sendError(exchange, 410, "{\"error\":\"Cursor is outside the retention window\"}");
                    return;
                }
            } catch (Exception e) {
                sendError(exchange, 500, "{\"error\":\"Internal server error\"}");
                return;
            }
        }

        List<ReviewEvent> events;
        try {
            events = eventRepository.queryEvents(repo, since, limit);
        } catch (Exception e) {
            sendError(exchange, 500, "{\"error\":\"Internal server error\"}");
            return;
        }

        long nextSince = events.isEmpty() ? since : events.get(events.size() - 1).sequenceNo();
        byte[] body = gson.toJson(new EventsResponse(events, nextSince)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private void sendError(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private String getParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) {
            return null;
        }
        for (String param : query.split("&")) {
            String[] parts = param.split("=", 2);
            if (parts.length == 2 && parts[0].equals(name)) {
                return parts[1];
            }
        }
        return null;
    }

    private long parseLongParam(HttpExchange exchange, String name, long defaultValue) {
        String value = getParam(exchange, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private int parseIntParam(HttpExchange exchange, String name, int defaultValue) {
        String value = getParam(exchange, name);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static final class EventsResponse {
        final List<ReviewEvent> events;
        final long nextSince;

        EventsResponse(List<ReviewEvent> events, long nextSince) {
            this.events = events;
            this.nextSince = nextSince;
        }
    }
}

