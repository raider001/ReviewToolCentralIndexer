package com.kalynx.centralindexer.http;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kalynx.centralindexer.db.ReviewRecord;
import com.kalynx.centralindexer.db.ReviewsIndexMapper;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Handles {@code GET /reviews}.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code since} — ISO 8601 instant; returns only reviews with {@code last_updated > since}</li>
 *   <li>{@code status} — comma-separated status values (e.g. {@code OPEN,APPROVED}); omit to return all</li>
 * </ul>
 *
 * <p>Response: {@code {"items": [{...}]}} where each item carries
 * {@code review_id}, {@code status}, {@code last_updated}, {@code review_branch},
 * {@code base_branch}, and {@code repositories}.
 */
public final class ReviewsHandler implements HttpHandler {

    private static final Type REPO_ENTRY_LIST_TYPE =
            new TypeToken<List<ReviewsIndexMapper.RepoEntry>>() {}.getType();

    private final ReviewsIndexRepository repository;
    private final MetricsCollector metrics;
    private final Gson gson;

    public ReviewsHandler(ReviewsIndexRepository repository) {
        this(repository, null);
    }

    public ReviewsHandler(ReviewsIndexRepository repository, MetricsCollector metrics) {
        this.repository = repository;
        this.metrics = metrics;
        this.gson = GsonFactory.getInstance();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendError(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        Map<String, String> params = parseQuery(exchange.getRequestURI().getQuery());

        Instant since = null;
        String sinceParam = params.get("since");
        if (sinceParam != null) {
            try {
                since = Instant.parse(sinceParam);
            } catch (DateTimeParseException e) {
                sendError(exchange, 400, "{\"error\":\"invalid 'since' parameter: must be ISO 8601\"}");
                return;
            }
        }

        List<String> statuses = null;
        String statusParam = params.get("status");
        if (statusParam != null && !statusParam.isBlank()) {
            statuses = List.of(statusParam.split(","));
        }

        List<ReviewRecord> records;
        try {
            long start = System.nanoTime();
            records = repository.query(since, statuses);
            if (metrics != null) metrics.recordReviewsQueryLatency((System.nanoTime() - start) / 1_000_000);
        } catch (Exception e) {
            sendError(exchange, 500, "{\"error\":\"internal server error\"}");
            return;
        }

        List<ReviewItem> items = new ArrayList<>();
        for (ReviewRecord rec : records) {
            List<ReviewsIndexMapper.RepoEntry> entries = Collections.emptyList();
            if (rec.repositoriesJson() != null) {
                try {
                    entries = gson.fromJson(rec.repositoriesJson(), REPO_ENTRY_LIST_TYPE);
                    if (entries == null) entries = Collections.emptyList();
                } catch (Exception ignored) {
                }
            }
            String reviewBranch = entries.isEmpty() ? null : entries.get(0).branchName;
            items.add(new ReviewItem(
                    rec.reviewId(),
                    rec.status(),
                    rec.lastUpdated() != null ? rec.lastUpdated().toString() : null,
                    reviewBranch,
                    null,
                    buildRepositories(entries)));
        }

        byte[] body = gson.toJson(new ReviewsResponse(items)).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private List<RepositoryEntry> buildRepositories(List<ReviewsIndexMapper.RepoEntry> entries) {
        LinkedHashMap<String, String> seen = new LinkedHashMap<>();
        for (ReviewsIndexMapper.RepoEntry e : entries) {
            String key = e.owner + "/" + e.repository;
            seen.putIfAbsent(key, e.repositoryUrl);
        }
        List<RepositoryEntry> result = new ArrayList<>();
        for (Map.Entry<String, String> e : seen.entrySet()) {
            result.add(new RepositoryEntry(e.getKey(), e.getValue()));
        }
        return result;
    }

    private void sendError(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.getResponseBody().close();
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> params = new HashMap<>();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                params.put(kv[0], kv[1]);
            }
        }
        return params;
    }

    private record RepositoryEntry(String repository, String repository_url) {}

    private record ReviewItem(String review_id, String status, String last_updated,
                              String review_branch, String base_branch,
                              List<RepositoryEntry> repositories) {}

    private record ReviewsResponse(List<ReviewItem> items) {}
}
