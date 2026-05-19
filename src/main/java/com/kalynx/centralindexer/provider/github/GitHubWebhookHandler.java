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
        String event = headers.getOrDefault("x-github-event", headers.get("X-GitHub-Event"));
        if (!"push".equals(event)) {
            return;
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        String repoFullName = json.getAsJsonObject("repository").get("full_name").getAsString();
        String secret = resolveSecret(repoFullName);

        String sigHeader = headers.getOrDefault(
                "x-hub-signature-256", headers.get("X-Hub-Signature-256"));
        if (!HmacSignatureVerifier.verify(secret, rawBody, sigHeader)) {
            log.warn("Invalid signature for GitHub push on repository {}", repoFullName);
            return;
        }

        String deliveryId = headers.getOrDefault(
                "x-github-delivery", headers.get("X-GitHub-Delivery"));
        String ref = json.get("ref").getAsString();
        String afterHash = json.has("after") ? json.get("after").getAsString() : null;
        String actorUser = json.has("pusher")
                ? json.getAsJsonObject("pusher").get("name").getAsString() : null;
        Instant timestamp = extractTimestamp(json);

        ParsedRef parsed = ReviewRefParser.parse(ref);
        if (parsed == null) {
            return;
        }

        ReviewEvent reviewEvent = buildEvent(parsed, repoFullName, actorUser,
                timestamp, afterHash, deliveryId);
        if (reviewEvent != null) {
            submit(reviewEvent);
        }
    }

    private ReviewEvent buildEvent(ParsedRef parsed, String repo, String actor,
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
                        ? Map.of("branch", parsed.branch(), "headSha", afterHash)
                        : Map.of("branch", parsed.branch());
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
}

