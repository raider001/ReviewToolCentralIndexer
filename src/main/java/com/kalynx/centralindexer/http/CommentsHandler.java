package com.kalynx.centralindexer.http;

import com.google.gson.Gson;
import com.kalynx.centralindexer.db.CommentEntry;
import com.kalynx.centralindexer.db.CommentsIndexRepository;
import com.kalynx.centralindexer.json.GsonFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles {@code GET /reviews/{reviewId}/comments}.
 *
 * <p>Returns all {@code comments_index} rows for the given review, joined with the
 * repository URL from {@code repositories}.
 *
 * <p>Response (200):
 * <pre>{@code
 * [
 *   {"repository_url": "...", "comment_id": "...", "last_updated": "..."},
 *   ...
 * ]
 * }</pre>
 *
 * <p>Response (404): review exists but has no indexed comments yet, or the path does not
 * match the pattern {@code /reviews/{reviewId}/comments}.
 *
 * <p>Registered at context {@code /reviews/} so the JDK server's longest-prefix routing
 * picks this handler for {@code /reviews/*} paths while {@code ReviewsHandler} continues
 * to handle the bare {@code /reviews} path.
 */
public final class CommentsHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(CommentsHandler.class);

    private final CommentsIndexRepository repository;
    private final Gson gson;

    /**
     * Constructs a {@code CommentsHandler} backed by the given repository.
     *
     * @param repository the repository used to query {@code comments_index}
     */
    public CommentsHandler(CommentsIndexRepository repository) {
        this.repository = repository;
        this.gson = GsonFactory.getInstance();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        String reviewId = extractReviewId(exchange.getRequestURI().getPath());
        if (reviewId == null) {
            sendError(exchange, 404, "{\"error\":\"not found\"}");
            return;
        }

        try {
            List<CommentEntry> entries = repository.findByReviewId(reviewId);
            if (entries.isEmpty()) {
                sendError(exchange, 404, "{\"error\":\"no comments indexed for this review\"}");
                return;
            }
            List<CommentResponse> body = entries.stream()
                    .map(e -> new CommentResponse(e.repositoryUrl(), e.commentId(),
                            e.lastUpdated() != null ? e.lastUpdated().toString() : null))
                    .toList();
            sendJson(exchange, 200, gson.toJson(body));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "{\"error\":\"service unavailable\"}");
        } catch (Exception e) {
            log.error("Error handling GET /reviews/{}/comments", reviewId, e);
            sendError(exchange, 500, "{\"error\":\"internal server error\"}");
        }
    }

    /**
     * Parses {@code /reviews/{reviewId}/comments} and returns the reviewId,
     * or {@code null} if the path does not match.
     */
    static String extractReviewId(String path) {
        if (path == null) return null;
        String[] parts = path.split("/", -1);
        // expected: ["", "reviews", "{reviewId}", "comments"]
        if (parts.length != 4) return null;
        if (!"reviews".equals(parts[1])) return null;
        if (!"comments".equals(parts[3])) return null;
        String id = parts[2];
        return (id == null || id.isBlank()) ? null : id;
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private void sendError(HttpExchange exchange, int status, String json) throws IOException {
        sendJson(exchange, status, json);
    }

    private record CommentResponse(String repository_url, String comment_id, String last_updated) {}
}
