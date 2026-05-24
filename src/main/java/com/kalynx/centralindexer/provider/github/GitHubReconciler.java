package com.kalynx.centralindexer.provider.github;

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
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Reconciles missed events for a single GitHub repository by paging through the
 * GitHub Events API and replaying any {@code PushEvent} entries whose ref matches
 * {@code refs/notes/reviews/*} and whose {@code created_at} is after {@code since}.
 *
 * <p>GitHub retains events for 90 days and returns at most 300 per query. Events
 * beyond this window are unrecoverable; the caller should log a warning if
 * {@code since} is older than 90 days.
 *
 * <p>Configuration keys read from {@link ProviderConfig#properties()}:
 * <ul>
 *   <li>{@code apiToken} — GitHub personal access token (required for 5,000 req/h)</li>
 * </ul>
 */
public final class GitHubReconciler {

    private static final Logger log = LoggerFactory.getLogger(GitHubReconciler.class);
    private static final String API_BASE = "https://api.github.com";

    private final HttpClient http;

    /**
     * Creates a reconciler using the JDK default HTTP client.
     */
    public GitHubReconciler() {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Creates a reconciler with an injected HTTP client (for testing).
     *
     * @param http the HTTP client to use for GitHub API requests
     */
    GitHubReconciler(HttpClient http) {
        this.http = http;
    }

    /**
     * Pages through the GitHub Events API for the repository and submits any
     * missed review-notes push events to the sink.
     *
     * @param repository the canonical {@code owner/repo} identifier
     * @param since      only events after this instant are submitted
     * @param config     the provider configuration (for the API token)
     * @param sink       the event sink to receive back-filled events
     */
    public void reconcile(String repository, Instant since, ProviderConfig config, EventSink sink) {
        String token = config.properties().get("apiToken");
        if (token == null || token.isBlank()) {
            log.warn("No apiToken configured for GitHub; skipping reconciliation of {}", repository);
            return;
        }

        int page = 1;
        boolean keepPaging = true;
        while (keepPaging) {
            String url = API_BASE + "/repos/" + repository + "/events?per_page=100&page=" + page;
            String body = fetchPage(url, token);
            if (body == null) {
                break;
            }
            JsonArray events = JsonParser.parseString(body).getAsJsonArray();
            if (events.isEmpty()) {
                break;
            }
            for (JsonElement el : events) {
                JsonObject event = el.getAsJsonObject();
                if (!"PushEvent".equals(event.get("type").getAsString())) {
                    continue;
                }
                Instant createdAt = Instant.parse(event.get("created_at").getAsString());
                if (!createdAt.isAfter(since)) {
                    keepPaging = false;
                    break;
                }
                processEvent(event, repository, createdAt, sink);
            }
            page++;
        }
    }

    private void processEvent(JsonObject event, String repository, Instant timestamp,
                               EventSink sink) {
        JsonObject payload = event.getAsJsonObject("payload");
        if (payload == null || !payload.has("ref")) {
            return;
        }
        String ref = payload.get("ref").getAsString();
        ParsedRef parsed = ReviewRefParser.parse(ref);
        if (parsed == null || parsed.type() != ReviewRefParser.RefType.NOTES) {
            return;
        }
        String pushId = payload.has("push_id")
                ? "github-reconcile-" + payload.get("push_id").getAsString() : null;
        EventType type = ReviewRefParser.mapNotesEventType(parsed.streamName(), false);
        ReviewEvent reviewEvent = new ReviewEvent(0L, timestamp, repository,
                type, parsed.reviewId(), null, pushId, Map.of());
        try {
            sink.submit(reviewEvent);
        } catch (RuntimeException e) {
            log.warn("Failed to submit reconciled GitHub event for {}: {}",
                    repository, e.getMessage());
        }
    }

    private String fetchPage(String url, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString());
            MetricsCollector mc = MetricsCollector.getInstance();
            if (mc != null) mc.recordProviderApiCall();
            if (response.statusCode() != 200) {
                log.warn("GitHub Events API returned {} for {}", response.statusCode(), url);
                return null;
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Error fetching GitHub events from {}: {} — {}", url, e.getClass().getSimpleName(), e.getMessage());
            log.debug("Full exception for {}", url, e);
            return null;
        }
    }
}

