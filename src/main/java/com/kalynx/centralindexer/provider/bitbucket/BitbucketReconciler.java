package com.kalynx.centralindexer.provider.bitbucket;

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
import java.util.Base64;
import java.util.Map;

/**
 * Reconciles missed events for a Bitbucket repository by enumerating current
 * {@code refs/notes/reviews/*} branches and submitting synthetic review events.
 *
 * <p>Both Bitbucket Cloud and Data Center are supported, selected by
 * {@code variant} in plugin properties ({@code cloud} or {@code datacenter}).
 *
 * <p>Configuration keys read from {@link ProviderConfig#properties()}:
 * <ul>
 *   <li>{@code variant} — {@code cloud} (default) or {@code datacenter}</li>
 *   <li>{@code username} / {@code appPassword} — Bitbucket Cloud credentials</li>
 *   <li>{@code baseUrl} — Data Center base URL (e.g. {@code https://bitbucket.example.com})</li>
 *   <li>{@code projectKey} — Data Center project key</li>
 * </ul>
 */
public final class BitbucketReconciler {

    private static final Logger log = LoggerFactory.getLogger(BitbucketReconciler.class);

    private final HttpClient http;

    /**
     * Creates a reconciler using the JDK default HTTP client.
     */
    public BitbucketReconciler() {
        this.http = HttpClient.newHttpClient();
    }

    BitbucketReconciler(HttpClient http) {
        this.http = http;
    }

    /**
     * Enumerates existing review note branches for the repository and submits a
     * {@code REVIEW_UPDATED} event for each reviewId found since {@code since}.
     *
     * @param repository the canonical {@code owner/repo} or Data Center {@code projectKey/slug}
     * @param since      events before this instant are skipped
     * @param config     the provider configuration
     * @param sink       the event sink
     */
    public void reconcile(String repository, Instant since, ProviderConfig config, EventSink sink) {
        String variant = config.properties().getOrDefault("variant", "cloud");
        String url = buildBranchListUrl(repository, config, variant);
        if (url == null) {
            return;
        }
        String body = fetchPage(url, config, variant);
        if (body == null) {
            return;
        }
        JsonObject response = JsonParser.parseString(body).getAsJsonObject();
        JsonArray values = extractValues(response, variant);
        if (values == null) {
            return;
        }
        for (JsonElement el : values) {
            String branchName = extractBranchName(el.getAsJsonObject(), variant);
            if (branchName == null) {
                continue;
            }
            ParsedRef parsed = ReviewRefParser.parse(branchName);
            if (parsed == null || parsed.type() != ReviewRefParser.RefType.NOTES) {
                continue;
            }
            ReviewEvent event = new ReviewEvent(0L, Instant.now(), repository,
                    EventType.REVIEW_UPDATED, parsed.reviewId(), null,
                    "bitbucket-reconcile-" + repository + ":" + parsed.reviewId(), Map.of());
            try {
                sink.submit(event);
            } catch (RuntimeException e) {
                log.warn("Failed to submit reconciled Bitbucket event for {}: {}",
                        repository, e.getMessage());
            }
        }
    }

    private String buildBranchListUrl(String repository, ProviderConfig config, String variant) {
        if ("datacenter".equals(variant)) {
            String baseUrl = config.properties().get("baseUrl");
            String projectKey = config.properties().get("projectKey");
            String slug = repository.contains("/") ? repository.split("/")[1] : repository;
            if (baseUrl == null || projectKey == null) {
                log.warn("Missing baseUrl or projectKey for Bitbucket DC reconciliation of {}",
                        repository);
                return null;
            }
            return baseUrl + "/rest/api/1.0/projects/" + projectKey
                    + "/repos/" + slug + "/branches?filterText=refs/notes/reviews/&limit=100";
        }
        String[] parts = repository.split("/", 2);
        if (parts.length < 2) {
            log.warn("Cannot parse workspace from repository '{}' for Bitbucket Cloud", repository);
            return null;
        }
        return "https://api.bitbucket.org/2.0/repositories/" + parts[0] + "/" + parts[1]
                + "/refs/branches?q=name+%7E+%22refs%2Fnotes%2Freviews%2F%22&pagelen=100";
    }

    private String fetchPage(String url, ProviderConfig config, String variant) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).GET();
            if ("datacenter".equals(variant)) {
                String user = config.properties().get("username");
                String pass = config.properties().get("appPassword");
                if (user != null && pass != null) {
                    String creds = Base64.getEncoder().encodeToString(
                            (user + ":" + pass).getBytes());
                    builder.header("Authorization", "Basic " + creds);
                }
            } else {
                String user = config.properties().get("username");
                String pass = config.properties().get("appPassword");
                if (user != null && pass != null) {
                    String creds = Base64.getEncoder().encodeToString(
                            (user + ":" + pass).getBytes());
                    builder.header("Authorization", "Basic " + creds);
                }
            }
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            MetricsCollector mc = MetricsCollector.getInstance();
            if (mc != null) mc.recordProviderApiCall();
            if (response.statusCode() != 200) {
                log.warn("Bitbucket API returned {} for {}", response.statusCode(), url);
                return null;
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Error fetching Bitbucket branches from {}: {}", url, e.getMessage());
            return null;
        }
    }

    private JsonArray extractValues(JsonObject response, String variant) {
        if ("datacenter".equals(variant) && response.has("values")) {
            return response.getAsJsonArray("values");
        }
        if (response.has("values")) {
            return response.getAsJsonArray("values");
        }
        return null;
    }

    private String extractBranchName(JsonObject branch, String variant) {
        try {
            if ("datacenter".equals(variant)) {
                return branch.get("displayId").getAsString();
            }
            return branch.get("name").getAsString();
        } catch (Exception e) {
            return null;
        }
    }
}

