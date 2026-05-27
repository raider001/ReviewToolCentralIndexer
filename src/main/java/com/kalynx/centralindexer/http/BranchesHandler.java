package com.kalynx.centralindexer.http;

import com.google.gson.Gson;
import com.kalynx.centralindexer.db.BranchRecord;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Handles {@code GET /branches}.
 *
 * <p>Provides a lightweight global branch listing for typeahead and discovery.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code q} — optional branch name prefix</li>
 *   <li>{@code repository} — optional {@code owner/repo} filter</li>
 *   <li>{@code limit} — optional; default {@value DEFAULT_LIMIT}, max {@value MAX_LIMIT}</li>
 *   <li>{@code cursor} — optional opaque pagination cursor from a previous response</li>
 * </ul>
 *
 * <p>Response (200):
 * <pre>{@code
 * {
 *   "branches": ["main", "feature/foo"],
 *   "next_cursor": "<opaque-base64>"   // null when no further pages
 * }
 * }</pre>
 *
 * <p>The cursor encodes the last returned row's {@code (owner, repository, branch_name)}
 * as a URL-safe Base64 string (no padding). Clients treat it as opaque.
 */
public final class BranchesHandler implements HttpHandler {

    private static final Logger log = LoggerFactory.getLogger(BranchesHandler.class);
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;

    private final BranchRepository branchRepository;
    private final Gson gson;

    /**
     * Constructs a {@code BranchesHandler} backed by the given repository.
     *
     * @param branchRepository the repository used to query the {@code branches} table
     */
    private final MetricsCollector metrics;

    public BranchesHandler(BranchRepository branchRepository) {
        this(branchRepository, null);
    }

    public BranchesHandler(BranchRepository branchRepository, MetricsCollector metrics) {
        this.branchRepository = branchRepository;
        this.metrics = metrics;
        this.gson = GsonFactory.getInstance();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "{\"error\":\"method not allowed\"}");
            return;
        }

        int limit;
        String limitStr = getParam(exchange, "limit");
        if (limitStr == null) {
            limit = DEFAULT_LIMIT;
        } else {
            try {
                limit = Integer.parseInt(limitStr);
            } catch (NumberFormatException e) {
                sendError(exchange, 400, "{\"error\":\"limit must be an integer\"}");
                return;
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                sendError(exchange, 400, "{\"error\":\"limit must be between 1 and 500\"}");
                return;
            }
        }

        String repositoryParam = getParam(exchange, "repository");
        String owner = null;
        String repository = null;
        if (repositoryParam != null) {
            int slash = repositoryParam.indexOf('/');
            if (slash < 1 || slash == repositoryParam.length() - 1) {
                sendError(exchange, 400, "{\"error\":\"repository must be in owner/repo format\"}");
                return;
            }
            owner = repositoryParam.substring(0, slash);
            repository = repositoryParam.substring(slash + 1);
        }

        String[] cursor = null;
        String cursorParam = getParam(exchange, "cursor");
        if (cursorParam != null) {
            cursor = decodeCursor(cursorParam);
            if (cursor == null || cursor.length != 3) {
                sendError(exchange, 400, "{\"error\":\"invalid cursor\"}");
                return;
            }
        }

        String prefix  = getParam(exchange, "q");
        boolean detailed = "true".equalsIgnoreCase(getParam(exchange, "detailed"));
        try {
            long start = System.nanoTime();
            List<BranchRecord> records = branchRepository.query(prefix, owner, repository, limit, cursor);
            if (metrics != null) metrics.recordBranchesQueryLatency((System.nanoTime() - start) / 1_000_000);
            String nextCursor = records.size() == limit
                    ? encodeCursor(records.get(records.size() - 1))
                    : null;
            List<String> branchNames = records.stream().map(BranchRecord::branchName).toList();
            if (detailed) {
                List<DetailedBranchRecord> detail = records.stream()
                        .map(r -> new DetailedBranchRecord(r.owner(), r.repository(), r.branchName()))
                        .toList();
                sendJson(exchange, 200, gson.toJson(new DetailedBranchesResponse(branchNames, detail, nextCursor)));
            } else {
                sendJson(exchange, 200, gson.toJson(new BranchesResponse(branchNames, nextCursor)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(exchange, 503, "{\"error\":\"service unavailable\"}");
        } catch (Exception e) {
            log.error("Error handling GET /branches", e);
            sendError(exchange, 500, "{\"error\":\"internal server error\"}");
        }
    }

    private static String encodeCursor(BranchRecord last) {
        String raw = last.owner() + "\0" + last.repository() + "\0" + last.branchName();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String[] decodeCursor(String encoded) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            String raw = new String(bytes, StandardCharsets.UTF_8);
            String[] parts = raw.split("\0", -1);
            return parts.length == 3 ? parts : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
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

    private static String getParam(HttpExchange exchange, String name) {
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

    private record BranchesResponse(List<String> branches, String next_cursor) {}
    private record DetailedBranchRecord(String owner, String repository, String branch_name) {}
    private record DetailedBranchesResponse(List<String> branches, List<DetailedBranchRecord> branch_records, String next_cursor) {}
}
