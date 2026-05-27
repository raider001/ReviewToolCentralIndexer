package com.kalynx.centralindexer.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Handles {@code GET /events/stream?repository=<repo>}.
 *
 * <p>Passing {@code repository=*} subscribes to all repositories at once.
 *
 * <p>On a valid request the handler:
 * <ol>
 *   <li>Sets SSE response headers ({@code Content-Type: text/event-stream},
 *       {@code Cache-Control: no-cache}, {@code X-Accel-Buffering: no}) and sends
 *       {@code 200 OK} with a streaming (chunked) body.</li>
 *   <li>Subscribes to {@link PublisherRegistry} and streams live events until the client
 *       disconnects or the thread is interrupted.</li>
 * </ol>
 *
 * <p>Clients that reconnect should re-call {@code GET /reviews} to catch any missed
 * updates before reopening this stream.
 *
 * <p>Each SSE frame is written as:
 * <pre>
 * event: &lt;eventType&gt;
 * data: &lt;fullEventJson&gt;
 *
 * </pre>
 */
public final class SseHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(SseHandler.class);
    private static final String PARAM_REPOSITORY = "repository";
    private static final String WILDCARD_REPO = "*";

    private final PublisherRegistry registry;
    private final MetricsCollector metrics;
    private final Gson gson;

    public SseHandler(PublisherRegistry registry) {
        this(registry, null);
    }

    public SseHandler(PublisherRegistry registry, MetricsCollector metrics) {
        this.registry = registry;
        this.metrics = metrics;
        this.gson = GsonFactory.getInstance();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String repo = getParam(exchange, PARAM_REPOSITORY);
        if (repo == null) {
            sendError(exchange, 400, "{\"error\":\"repository parameter is required\"}");
            return;
        }

        String clientAddress = exchange.getRemoteAddress() != null
                ? exchange.getRemoteAddress().toString()
                : "unknown";

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);

        log.info("SSE client connected: address='{}' repo='{}'", clientAddress, repo);
        if (metrics != null) metrics.incrementConnectedClients(clientAddress);
        try (OutputStream out = exchange.getResponseBody()) {
            streamLiveEvents(out, repo);
        } catch (Exception e) {
            log.debug("SSE stream closed for client '{}' repo='{}': {}", clientAddress, repo, e.getMessage());
        } finally {
            if (metrics != null) metrics.decrementConnectedClients(clientAddress);
            log.info("SSE client disconnected: address='{}' repo='{}'", clientAddress, repo);
        }
    }

    private static final byte[] KEEP_ALIVE_FRAME = ":\n\n".getBytes(StandardCharsets.UTF_8);

    private void streamLiveEvents(OutputStream out, String repo) throws Exception {
        SseSubscriber subscriber = new SseSubscriber();
        if (WILDCARD_REPO.equals(repo)) {
            registry.subscribeAll(subscriber);
        } else {
            registry.subscribe(repo, subscriber);
        }
        try {
            while (!Thread.currentThread().isInterrupted()) {
                ReviewEvent event = subscriber.pollEvent(1, TimeUnit.SECONDS);
                if (event == null) {
                    if (subscriber.isTerminated()) {
                        break;
                    }
                    out.write(KEEP_ALIVE_FRAME);
                    out.flush();
                    continue;
                }
                writeSseFrame(out, event);
                out.flush();
            }
        } finally {
            subscriber.cancel();
        }
    }

    private void writeSseFrame(OutputStream out, ReviewEvent event) throws IOException {
        String sseName = toSseName(event.eventType());
        String data = toSseData(event);
        String frame = "event: " + sseName + "\n" + "data: " + data + "\n\n";
        long start = System.nanoTime();
        out.write(frame.getBytes(StandardCharsets.UTF_8));
        if (metrics != null) {
            metrics.recordSseWriteLatency((System.nanoTime() - start) / 1_000_000);
            metrics.recordSseEvent(sseName);
        }
    }

    static String toSseName(EventType type) {
        return switch (type) {
            case REVIEW_COMMENT_ADDED   -> "comment.added";
            case REVIEW_COMMENT_UPDATED -> "comment.updated";
            default                     -> type.name();
        };
    }

    String toSseData(ReviewEvent event) {
        String sseName = toSseName(event.eventType());
        if (event.eventType() == EventType.REVIEW_COMMENT_ADDED
                || event.eventType() == EventType.REVIEW_COMMENT_UPDATED) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", sseName);
            obj.addProperty("review_id", event.reviewId());
            String repoUrl = event.payload().get("repository_url");
            String commentId = event.payload().get("comment_id");
            if (repoUrl != null) obj.addProperty("repository_url", repoUrl);
            if (commentId != null) obj.addProperty("comment_id", commentId);
            return gson.toJson(obj);
        }
        return gson.toJson(event);
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
                return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    static final class SseSubscriber implements Flow.Subscriber<ReviewEvent> {

        private static final Object TERMINAL = new Object();
        private final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private volatile Flow.Subscription subscription;
        private volatile boolean terminated = false;

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
            terminated = true;
            queue.offer(TERMINAL);
        }

        @Override
        public void onComplete() {
            terminated = true;
            queue.offer(TERMINAL);
        }

        ReviewEvent pollEvent(long timeout, TimeUnit unit) throws InterruptedException {
            Object item = queue.poll(timeout, unit);
            if (item == null || item == TERMINAL) {
                return null;
            }
            return (ReviewEvent) item;
        }

        boolean isTerminated() {
            return terminated;
        }

        void cancel() {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }
}