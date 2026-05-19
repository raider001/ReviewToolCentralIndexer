package com.kalynx.centralindexer.provider.gitlab;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.provider.common.ReviewRefParser;
import com.kalynx.centralindexer.provider.common.ReviewRefParser.ParsedRef;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.WebhookHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Handles GitLab push webhook deliveries on {@code POST /webhooks/gitlab}.
 *
 * <p>GitLab passes the webhook secret as a plain header rather than an HMAC digest.
 * The comparison uses {@link MessageDigest#isEqual} to prevent timing attacks.
 *
 * <p>Headers used:
 * <ul>
 *   <li>{@code X-Gitlab-Token} — compared against the configured secret</li>
 *   <li>{@code X-Gitlab-Event} — must be {@code "Push Hook"}</li>
 *   <li>{@code X-Gitlab-Event-UUID} — delivery ID for deduplication</li>
 * </ul>
 */
public final class GitLabWebhookHandler implements WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookHandler.class);

    private final ProviderConfig config;
    private final EventSink sink;
    private final Set<String> seenReviewIds = Collections.synchronizedSet(new HashSet<>());

    /**
     * Creates a handler for GitLab push events.
     *
     * @param config the provider configuration carrying webhook secrets
     * @param sink   the event sink for processed review events
     */
    public GitLabWebhookHandler(ProviderConfig config, EventSink sink) {
        this.config = config;
        this.sink = sink;
    }

    @Override
    public void handle(Map<String, String> headers, byte[] rawBody) {
        String event = headers.getOrDefault("x-gitlab-event", headers.get("X-Gitlab-Event"));
        if (!"Push Hook".equals(event)) {
            return;
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        String repoFullName = json.getAsJsonObject("repository").get("name").getAsString();
        String secret = resolveSecret(repoFullName);
        String tokenHeader = headers.getOrDefault("x-gitlab-token", headers.get("X-Gitlab-Token"));

        if (!verifyToken(secret, tokenHeader)) {
            log.warn("Invalid token for GitLab push on repository {}", repoFullName);
            return;
        }

        String deliveryId = headers.getOrDefault(
                "x-gitlab-event-uuid", headers.get("X-Gitlab-Event-UUID"));
        String ref = json.get("ref").getAsString();
        String afterHash = json.has("after") ? json.get("after").getAsString() : null;
        String actorUser = json.has("user_username")
                ? json.get("user_username").getAsString() : null;
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
                Map<String, String> payload = (!deleted && afterHash != null)
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
            log.warn("Failed to submit GitLab event for {}: {}",
                    event.repository(), e.getMessage());
        }
    }

    private boolean verifyToken(String expected, String actual) {
        if (expected == null || expected.isBlank() || actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private Instant extractTimestamp(JsonObject json) {
        try {
            if (json.has("commits") && json.getAsJsonArray("commits").size() > 0) {
                String ts = json.getAsJsonArray("commits").get(0)
                        .getAsJsonObject().get("timestamp").getAsString();
                return Instant.parse(ts);
            }
        } catch (Exception ignored) {
        }
        return Instant.now();
    }

    private String resolveSecret(String repoName) {
        String perRepo = config.properties().get(repoName + ".webhookSecret");
        if (perRepo != null) {
            return perRepo;
        }
        return config.properties().getOrDefault("webhookSecret", "");
    }
}

