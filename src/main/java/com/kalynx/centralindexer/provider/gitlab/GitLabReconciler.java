package com.kalynx.centralindexer.provider.gitlab;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.provider.common.ReviewRefParser;
import com.kalynx.centralindexer.provider.common.ReviewRefParser.ParsedRef;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

/**
 * Reconciles missed events for a GitLab repository by enumerating current
 * {@code refs/notes/reviews/*} branches via the GitLab Branches API and
 * submitting synthetic review events for each.
 *
 * <p>Configuration keys read from {@link ProviderConfig#properties()}:
 * <ul>
 *   <li>{@code baseUrl} — GitLab host (default {@code https://gitlab.com})</li>
 *   <li>{@code projectId} — numeric project ID or {@code namespace/repo} path</li>
 *   <li>{@code apiToken} — personal access token with {@code read_repository} scope</li>
 * </ul>
 */
public final class GitLabReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitLabReconciler.class);

    private final HttpClient http;
    private final MetricsCollector metrics;

    /**
     * Creates a reconciler using the JDK default HTTP client.
     *
     * @param metrics the metrics collector for API call tracking; may be {@code null}
     */
    public GitLabReconciler(MetricsCollector metrics) {
        this.http = HttpClient.newHttpClient();
        this.metrics = metrics;
    }

    /** No-arg constructor for tests and external SPI loading. */
    public GitLabReconciler() {
        this((MetricsCollector) null);
    }

    GitLabReconciler(HttpClient http, MetricsCollector metrics) {
        this.http = http;
        this.metrics = metrics;
    }

    /**
     * Enumerates existing review note branches and submits a {@code REVIEW_UPDATED}
     * event for each reviewId.
     *
     * @param repository the canonical {@code owner/repo} identifier
     * @param since      events before this instant are skipped (not used for GitLab
     *                   branch enumeration, but retained for interface consistency)
     * @param config     the provider configuration
     * @param sink       the event sink
     */
    public void reconcile(String repository, Instant since, ProviderConfig config, EventSink sink) {
        String token = config.properties().get("apiToken");
        if (token == null || token.isBlank()) {
            LOGGER.warn("No apiToken configured for GitLab; skipping reconciliation of {}", repository);
            return;
        }
        String baseUrl = config.properties().getOrDefault("baseUrl", "https://gitlab.com");
        String projectId = config.properties().getOrDefault("projectId",
                repository.replace("/", "%2F"));
        String url = baseUrl + "/api/v4/projects/" + projectId
                + "/repository/branches?search=refs%2Fnotes%2Freviews%2F&per_page=100";

        String body = fetchPage(url, token);
        if (body == null) {
            return;
        }
        JsonArray branches = JsonParser.parseString(body).getAsJsonArray();
        for (JsonElement el : branches) {
            JsonObject branch = el.getAsJsonObject();
            String branchName = branch.get("name").getAsString();
            ParsedRef parsed = ReviewRefParser.parse(branchName);
            if (parsed == null || parsed.type() != ReviewRefParser.RefType.NOTES) {
                continue;
            }
            ReviewEvent event = new ReviewEvent(0L, Instant.now(), repository,
                    EventType.REVIEW_UPDATED, parsed.reviewId(), null,
                    "gitlab-reconcile-" + repository + ":" + parsed.reviewId(), Map.of());
            try {
                sink.submit(event);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to submit reconciled GitLab event for {}: {}",
                        repository, e.getMessage());
            }
        }
    }

    private String fetchPage(String url, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("PRIVATE-TOKEN", token)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (metrics != null) metrics.recordProviderApiCall();
            if (response.statusCode() != 200) {
                LOGGER.warn("GitLab API returned {} for {}", response.statusCode(), url);
                return null;
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("Error fetching GitLab branches from {}: {}", url, e.getMessage());
            return null;
        }
    }
}

