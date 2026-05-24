package com.kalynx.centralindexer.http;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.json.GsonFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Handles {@code GET /repositories} and {@code POST /repositories}.
 *
 * <p>{@code GET /repositories} returns all registered repositories:
 * <pre>{@code
 * {
 *   "items": [
 *     {"owner": "my-org", "repository": "my-repo", "url": "https://..."}
 *   ]
 * }
 * }</pre>
 *
 * <p>{@code POST /repositories} registers or updates a repository. Request body:
 * <pre>{@code
 * {"owner": "my-org", "repository": "my-repo", "url": "https://..."}
 * }</pre>
 * Returns {@code 201 Created} on first registration, {@code 200 OK} on update.
 * Returns {@code 400 Bad Request} if any required field is missing or blank.
 */
public final class RepositoriesHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(RepositoriesHandler.class);

    private final RepositoriesRepository repository;
    private final Gson gson;

    public RepositoriesHandler(RepositoriesRepository repository) {
        this.repository = repository;
        this.gson = GsonFactory.getInstance();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        switch (method.toUpperCase()) {
            case "GET"  -> handleGet(exchange);
            case "POST" -> handlePost(exchange);
            default     -> sendError(exchange, 405, "{\"error\":\"method not allowed\"}");
        }
    }

    private void handleGet(HttpExchange exchange) throws IOException {
        try {
            List<RepositoryRecord> records = repository.findAll();
            List<RepositoryItem> items = records.stream()
                .map(r -> new RepositoryItem(r.owner(), r.repository(), r.url()))
                .toList();
            sendJson(exchange, 200, gson.toJson(new RepositoriesResponse(items)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "{\"error\":\"service unavailable\"}");
        } catch (Exception e) {
            log.error("Error handling GET /repositories", e);
            sendError(exchange, 500, "{\"error\":\"internal server error\"}");
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        byte[] rawBody = exchange.getRequestBody().readAllBytes();
        String body = new String(rawBody, StandardCharsets.UTF_8);

        String owner;
        String repo;
        String url;
        try {
            JsonObject obj = JsonParser.parseString(body).getAsJsonObject();
            owner = getStringField(obj, "owner");
            repo  = getStringField(obj, "repository");
            url   = getStringField(obj, "url");
        } catch (Exception e) {
            sendError(exchange, 400, "{\"error\":\"invalid JSON body\"}");
            return;
        }

        if (owner == null || owner.isBlank()) {
            sendError(exchange, 400, "{\"error\":\"missing required field: owner\"}");
            return;
        }
        if (repo == null || repo.isBlank()) {
            sendError(exchange, 400, "{\"error\":\"missing required field: repository\"}");
            return;
        }
        if (url == null || url.isBlank()) {
            sendError(exchange, 400, "{\"error\":\"missing required field: url\"}");
            return;
        }

        try {
            boolean existed = repositoryExists(owner, repo);
            repository.upsert(owner, repo, url);
            int status = existed ? 200 : 201;
            sendJson(exchange, status, gson.toJson(new RepositoryItem(owner, repo, url)));
            log.info("Repository {}/{} registered ({})", owner, repo, existed ? "updated" : "created");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "{\"error\":\"service unavailable\"}");
        } catch (Exception e) {
            log.error("Error handling POST /repositories", e);
            sendError(exchange, 500, "{\"error\":\"internal server error\"}");
        }
    }

    private boolean repositoryExists(String owner, String repo) {
        try {
            return repository.findAll().stream()
                .anyMatch(r -> r.owner().equals(owner) && r.repository().equals(repo));
        } catch (Exception e) {
            return false;
        }
    }

    private static String getStringField(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private void sendError(HttpExchange exchange, int status, String json) throws IOException {
        sendJson(exchange, status, json);
    }

    private record RepositoryItem(String owner, String repository, String url) {}
    private record RepositoriesResponse(List<RepositoryItem> items) {}
}
