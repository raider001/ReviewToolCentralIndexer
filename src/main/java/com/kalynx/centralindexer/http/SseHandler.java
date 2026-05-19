package com.kalynx.centralindexer.http;

import com.google.gson.Gson;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Handles {@code GET /events/stream?repository=<repo>&since=<n>}.
 *
 * <p>On a valid request the handler:
 * <ol>
 *   <li>Optionally validates the cursor via {@link EventRepository#hasEventAt} when
 *       {@code since > 0}, returning {@code 410 Gone} if the event no longer exists.</li>
 *   <li>Sets SSE response headers ({@code Content-Type: text/event-stream},
 *       {@code Cache-Control: no-cache}, {@code X-Accel-Buffering: no}) and sends
 *       {@code 200 OK} with a streaming (chunked) body.</li>
 *   <li>Replays stored events with {@code sequence_no > since} via
 *       {@link EventRepository#queryEvents}.</li>
 *   <li>Subscribes to {@link PublisherRegistry} and streams live events until the client
 *       disconnects or the thread is interrupted.</li>
 * </ol>
 *
 * <p>If {@code ?since=} is absent but the request carries a {@code Last-Event-ID} header,
 * that header value is used as the cursor (behavior 5.12). An explicit {@code ?since=}
 * always takes precedence.
 *
 * <p>Each SSE frame is written as:
 * <pre>
 * id: &lt;sequenceNo&gt;
 * event: &lt;eventType&gt;
 * data: &lt;fullEventJson&gt;
 *
 * </pre>
 */
public final class SseHandler implements HttpHandler {

    private static final String PARAM_REPOSITORY = "repository";
    private static final String PARAM_SINCE = "since";
    private static final String HEADER_LAST_EVENT_ID = "Last-Event-ID";

    private final EventRepository eventRepository;
    private final PublisherRegistry registry;
    private final Gson gson;

    /**
     * Constructs an {@code SseHandler} backed by the given repository and registry.
     *
     * @param eventRepository source for event replay and cursor validation
     * @param registry        live event fan-out registry
     */
    public SseHandler(EventRepository eventRepository, PublisherRegistry registry) {
        this.eventRepository = eventRepository;
        this.registry = registry;
        this.gson = GsonFactory.getInstance();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String repo = getParam(exchange, PARAM_REPOSITORY);
        if (repo == null) {
            sendError(exchange, 400, "{\"error\":\"repository parameter is required\"}");
            return;
        }

        long since = parseSince(exchange);

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

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream out = exchange.getResponseBody()) {
            replayStoredEvents(out, repo, since);
            streamLiveEvents(out, repo);
        } catch (Exception e) {
            // client disconnected or interrupted — normal SSE lifecycle
        }
    }

    private void replayStoredEvents(OutputStream out, String repo, long since) throws Exception {
        List<ReviewEvent> stored = eventRepository.queryEvents(repo, since, Integer.MAX_VALUE);
        for (ReviewEvent event : stored) {
            writeSseFrame(out, event);
        }
        out.flush();
    }

    private void streamLiveEvents(OutputStream out, String repo) throws Exception {
        SseSubscriber subscriber = new SseSubscriber();
        registry.subscribe(repo, subscriber);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ReviewEvent event = subscriber.pollEvent(1, TimeUnit.SECONDS);
                if (event == null) {
                    break;
                }
                writeSseFrame(out, event);
                out.flush();
            }
        } finally {
            subscriber.cancel();
        }
    }

    private void writeSseFrame(OutputStream out, ReviewEvent event) throws IOException {
        String frame = "id: " + event.sequenceNo() + "\n"
                + "event: " + event.eventType().name() + "\n"
                + "data: " + gson.toJson(event) + "\n\n";
        out.write(frame.getBytes(StandardCharsets.UTF_8));
    }

    private void sendError(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private long parseSince(HttpExchange exchange) {
        String sinceParam = getParam(exchange, PARAM_SINCE);
        if (sinceParam != null) {
            try {
                return Long.parseLong(sinceParam);
            } catch (NumberFormatException ignored) {
            }
        }
        String lastEventId = exchange.getRequestHeaders().getFirst(HEADER_LAST_EVENT_ID);
        if (lastEventId != null) {
            try {
                return Long.parseLong(lastEventId);
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
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

    static final class SseSubscriber implements Flow.Subscriber<ReviewEvent> {

        private static final Object TERMINAL = new Object();
        private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private volatile Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ReviewEvent item) {
            queue.offer(item);
        }

        @Override
        public void onError(Throwable t) {
            queue.offer(TERMINAL);
        }

        @Override
        public void onComplete() {
            queue.offer(TERMINAL);
        }

        ReviewEvent pollEvent(long timeout, TimeUnit unit) throws InterruptedException {
            Object item = queue.poll(timeout, unit);
            if (item == null || item == TERMINAL) {
                return null;
            }
            return (ReviewEvent) item;
        }

        void cancel() {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }
}

