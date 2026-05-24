package com.kalynx.centralindexer.provider.github;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.provider.common.HmacSignatureVerifier;
import com.kalynx.centralindexer.provider.common.ReviewRefParser;
import com.kalynx.centralindexer.provider.common.ReviewRefParser.ParsedRef;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.WebhookHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Handles GitHub push webhook deliveries on {@code POST /webhooks/github}.
 *
 * <p>Processing steps:
 * <ol>
 *   <li>Verify the {@code X-Hub-Signature-256} HMAC-SHA256 header.</li>
 *   <li>Ignore non-push events ({@code X-GitHub-Event} != {@code "push"}).</li>
 *   <li>Parse the ref; discard events whose ref matches neither rule.</li>
 *   <li>Map to a {@link ReviewEvent} and submit via {@link EventSink}.</li>
 * </ol>
 *
 * <p>The webhook secret is resolved per-repository: first
 * {@code {owner/repo}.webhookSecret} is tried, then the global {@code webhookSecret}
 * from {@code plugin.properties}.
 */
public final class GitHubWebhookHandler implements WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookHandler.class);

    private final ProviderConfig config;
    private final EventSink sink;
    private final Set<String> seenReviewIds = Collections.synchronizedSet(new HashSet<>());

    /**
     * Creates a handler that verifies signatures and routes events to the given sink.
     *
     * @param config the provider configuration carrying webhook secrets and API tokens
     * @param sink   the event sink to submit parsed review events to
     */
    public GitHubWebhookHandler(ProviderConfig config, EventSink sink) {
        this.config = config;
        this.sink = sink;
    }

    @Override
    public void handle(Map<String, String> headers, byte[] rawBody) {
        String event = headers.get("x-github-event");
        String deliveryId = headers.getOrDefault("x-github-delivery", "-");

        if (!"push".equals(event)) {
            log.debug("Ignoring GitHub event type '{}' (delivery='{}')", event, deliveryId);
            return;
        }

        try {
            handlePush(rawBody, headers, deliveryId);
        } catch (Exception e) {
            log.error("Failed to handle GitHub push delivery='{}': {}", deliveryId, e.getMessage(), e);
        }
    }

    private void handlePush(byte[] rawBody, Map<String, String> headers, String deliveryId) {
        String body = extractJsonBody(rawBody, headers);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        JsonObject repository = json.getAsJsonObject("repository");
        String repoFullName = repository.get("full_name").getAsString();
        String repoUrl = extractRepoUrl(repository);
        String secret = resolveSecret(repoFullName);

        String sigHeader = headers.get("x-hub-signature-256");
        if (!HmacSignatureVerifier.verify(secret, rawBody, sigHeader)) {
            log.warn("Invalid signature for GitHub push on repository {}", repoFullName);
            return;
        }

        String ref = json.get("ref").getAsString();
        String afterHash = json.has("after") ? json.get("after").getAsString() : null;
        String actorUser = json.has("pusher")
                ? json.getAsJsonObject("pusher").get("name").getAsString() : null;
        Instant timestamp = extractTimestamp(json);

        log.info("GitHub push: repo='{}' ref='{}' actor='{}' delivery='{}'",
                repoFullName, ref, actorUser, deliveryId);

        ParsedRef parsed = ReviewRefParser.parse(ref);
        if (parsed == null) {
            log.debug("Push ref '{}' on '{}' does not match a review ref — ignored", ref, repoFullName);
            return;
        }

        ReviewEvent reviewEvent = buildEvent(parsed, repoFullName, repoUrl, actorUser,
                timestamp, afterHash, deliveryId);
        if (reviewEvent != null) {
            log.info("Submitting event: type='{}' repo='{}' reviewId='{}' delivery='{}'",
                    reviewEvent.eventType(), reviewEvent.repository(),
                    reviewEvent.reviewId(), deliveryId);
            submit(reviewEvent);
        }
    }

    private ReviewEvent buildEvent(ParsedRef parsed, String repo, String repoUrl, String actor,
                                   Instant timestamp, String afterHash, String deliveryId) {
        switch (parsed.type()) {
            case NOTES -> {
                String key = repo + ":" + parsed.reviewId();
                boolean isFirst = seenReviewIds.add(key);
                EventType type = ReviewRefParser.mapNotesEventType(parsed.streamName(), isFirst);
                return new ReviewEvent(0L, timestamp, repo, type,
                        parsed.reviewId(), actor, deliveryId, Map.of());
            }
            case HEADS -> {
                boolean deleted = ReviewRefParser.isBranchDeletion(afterHash);
                EventType type = deleted ? EventType.BRANCH_DELETED : EventType.BRANCH_UPDATED;
                Map<String, String> payload = (afterHash != null && !deleted)
                        ? Map.of("repository_url", repoUrl, "branch_name", parsed.branch(), "head_commit", afterHash)
                        : Map.of("repository_url", repoUrl, "branch_name", parsed.branch());
                return new ReviewEvent(0L, timestamp, repo, type,
                        null, actor, deliveryId, payload);
            }
            default -> { return null; }
        }
    }

    private void submit(ReviewEvent event) {
        try {
            sink.submit(event);
        } catch (RuntimeException e) {
            log.warn("Failed to submit GitHub event for repository {}: {}",
                    event.repository(), e.getMessage());
        }
    }

    // GitHub sends application/x-www-form-urlencoded for some webhook configurations,
    // wrapping the JSON as payload=<url-encoded-json>.
    private String extractJsonBody(byte[] rawBody, Map<String, String> headers) {
        String contentType = headers.getOrDefault("content-type", "");
        String body = new String(rawBody, StandardCharsets.UTF_8);
        if (contentType.startsWith("application/x-www-form-urlencoded")) {
            for (String param : body.split("&")) {
                if (param.startsWith("payload=")) {
                    return URLDecoder.decode(param.substring("payload=".length()), StandardCharsets.UTF_8);
                }
            }
        }
        return body;
    }

    private Instant extractTimestamp(JsonObject json) {
        try {
            if (json.has("head_commit") && !json.get("head_commit").isJsonNull()) {
                String ts = json.getAsJsonObject("head_commit").get("timestamp").getAsString();
                return Instant.parse(ts);
            }
        } catch (Exception ignored) {
        }
        return Instant.now();
    }

    private String resolveSecret(String repoFullName) {
        String perRepo = config.properties().get(repoFullName + ".webhookSecret");
        if (perRepo != null) {
            return perRepo;
        }
        return config.properties().getOrDefault("webhookSecret", "");
    }

    private String extractRepoUrl(JsonObject repository) {
        // Prefer clone_url (HTTPS) over html_url
        if (repository.has("clone_url") && !repository.get("clone_url").isJsonNull()) {
            return repository.get("clone_url").getAsString();
        }
        if (repository.has("html_url") && !repository.get("html_url").isJsonNull()) {
            return repository.get("html_url").getAsString();
        }
        // Fallback: construct from full_name
        return "https://github.com/" + repository.get("full_name").getAsString();
    }
}
