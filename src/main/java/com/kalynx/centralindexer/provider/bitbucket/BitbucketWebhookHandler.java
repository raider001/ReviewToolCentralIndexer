package com.kalynx.centralindexer.provider.bitbucket;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
 * Handles Bitbucket push webhook deliveries on {@code POST /webhooks/bitbucket}.
 *
 * <p>Supports both Bitbucket Cloud ({@code X-Event-Key: repo:push}) and
 * Bitbucket Data Center ({@code X-Event-Key: repo:refs_changed}).
 * The payload format differs between variants and is detected automatically.
 *
 * <p>Signature header: {@code X-Hub-Signature: sha256=<hex>} (present on Cloud and
 * Data Center 7.x+). Requests without a signature are rejected unless
 * {@code allowUnsigned=true} is set in plugin properties.
 */
public final class BitbucketWebhookHandler implements WebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(BitbucketWebhookHandler.class);

    private final ProviderConfig config;
    private final EventSink sink;
    private final Set<String> seenReviewIds = Collections.synchronizedSet(new HashSet<>());

    /**
     * Creates a handler for Bitbucket push events.
     *
     * @param config the provider configuration carrying webhook secrets
     * @param sink   the event sink for processed review events
     */
    public BitbucketWebhookHandler(ProviderConfig config, EventSink sink) {
        this.config = config;
        this.sink = sink;
    }

    @Override
    public void handle(Map<String, String> headers, byte[] rawBody) {
        String eventKey = headers.get("x-event-key");
        if (eventKey == null || (!eventKey.startsWith("repo:push")
                && !eventKey.startsWith("repo:refs_changed"))) {
            return;
        }

        String body = new String(rawBody, StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(body).getAsJsonObject();

        String repoName = extractRepoName(json);
        String secret = resolveSecret(repoName);
        String sigHeader = headers.get("x-hub-signature");

        if (sigHeader != null) {
            if (!HmacSignatureVerifier.verify(secret, rawBody, sigHeader)) {
                log.warn("Invalid signature for Bitbucket push on repository {}", repoName);
                return;
            }
        } else if (!Boolean.parseBoolean(config.properties().getOrDefault("allowUnsigned", "false"))) {
            log.warn("Missing signature for Bitbucket push on {}; rejecting", repoName);
            return;
        }

        String deliveryId = headers.get("x-request-uuid");
        String repoUrl = extractRepoUrl(json);
        processPayload(json, repoName, repoUrl, deliveryId);
    }

    private void processPayload(JsonObject json, String repoName, String repoUrl, String deliveryId) {
        if (json.has("push")) {
            processCloudPayload(json, repoName, repoUrl, deliveryId);
        } else if (json.has("refChanges")) {
            processDataCenterPayload(json, repoName, repoUrl, deliveryId);
        }
    }

    private void processCloudPayload(JsonObject json, String repoName, String repoUrl, String deliveryId) {
        JsonArray changes = json.getAsJsonObject("push").getAsJsonArray("changes");
        for (JsonElement el : changes) {
            JsonObject change = el.getAsJsonObject();
            boolean deleted = change.get("new").isJsonNull();
            String ref;
            String headSha = null;
            if (deleted) {
                if (change.has("old") && !change.get("old").isJsonNull()) {
                    ref = change.getAsJsonObject("old").get("name").getAsString();
                } else {
                    continue;
                }
            } else {
                JsonObject newRef = change.getAsJsonObject("new");
                ref = newRef.get("name").getAsString();
                if (newRef.has("target") && !newRef.get("target").isJsonNull()) {
                    headSha = newRef.getAsJsonObject("target").get("hash").getAsString();
                }
            }
            Instant timestamp = extractCloudTimestamp(change);
            String actor = extractCloudActor(json);
            processRef(ref, repoName, repoUrl, actor, timestamp, headSha, deleted, deliveryId);
        }
    }

    private void processDataCenterPayload(JsonObject json, String repoName, String repoUrl, String deliveryId) {
        JsonArray changes = json.getAsJsonArray("refChanges");
        for (JsonElement el : changes) {
            JsonObject change = el.getAsJsonObject();
            String ref = change.get("refId").getAsString();
            boolean deleted = "DELETE".equals(change.get("type").getAsString());
            String headSha = deleted ? null : change.get("toHash").getAsString();
            String actor = json.has("actor")
                    ? json.getAsJsonObject("actor").get("name").getAsString() : null;
            processRef(ref, repoName, repoUrl, actor, Instant.now(), headSha, deleted, deliveryId);
        }
    }

    private void processRef(String ref, String repo, String repoUrl, String actor, Instant timestamp,
                             String headSha, boolean deleted, String deliveryId) {
        ParsedRef parsed = ReviewRefParser.parse(ref);
        if (parsed == null) {
            return;
        }
        ReviewEvent event = buildEvent(parsed, repo, repoUrl, actor, timestamp, headSha, deleted, deliveryId);
        if (event != null) {
            submit(event);
        }
    }

    private ReviewEvent buildEvent(ParsedRef parsed, String repo, String repoUrl, String actor,
                                   Instant timestamp, String headSha, boolean deleted,
                                   String deliveryId) {
        switch (parsed.type()) {
            case NOTES -> {
                String key = repo + ":" + parsed.reviewId();
                boolean isFirst = seenReviewIds.add(key);
                EventType type = ReviewRefParser.mapNotesEventType(parsed.streamName(), isFirst);
                return new ReviewEvent(0L, timestamp, repo, type,
                        parsed.reviewId(), actor, deliveryId, Map.of());
            }
            case HEADS -> {
                EventType type = deleted ? EventType.BRANCH_DELETED : EventType.BRANCH_UPDATED;
                Map<String, String> payload = (!deleted && headSha != null)
                        ? Map.of("repository_url", repoUrl, "branch_name", parsed.branch(), "head_commit", headSha)
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
            log.warn("Failed to submit Bitbucket event for {}: {}",
                    event.repository(), e.getMessage());
        }
    }

    private String extractRepoName(JsonObject json) {
        JsonObject repo = json.getAsJsonObject("repository");
        if (repo.has("full_name")) {
            return repo.get("full_name").getAsString();
        }
        if (repo.has("slug")) {
            return repo.get("slug").getAsString();
        }
        return repo.get("name").getAsString();
    }

    private Instant extractCloudTimestamp(JsonObject change) {
        try {
            if (change.has("commits") && change.getAsJsonArray("commits").size() > 0) {
                String date = change.getAsJsonArray("commits").get(0)
                        .getAsJsonObject().get("date").getAsString();
                return Instant.parse(date);
            }
        } catch (Exception ignored) {
        }
        return Instant.now();
    }

    private String extractCloudActor(JsonObject json) {
        try {
            if (json.has("actor")) {
                JsonObject actor = json.getAsJsonObject("actor");
                return actor.has("nickname") ? actor.get("nickname").getAsString() : null;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String resolveSecret(String repoName) {
        String perRepo = config.properties().get(repoName + ".webhookSecret");
        if (perRepo != null) {
            return perRepo;
        }
        return config.properties().getOrDefault("webhookSecret", "");
    }

    private String extractRepoUrl(JsonObject json) {
        JsonObject repository = json.getAsJsonObject("repository");
        // Bitbucket Cloud: links.clone[].href
        if (repository.has("links") && !repository.get("links").isJsonNull()) {
            JsonObject links = repository.getAsJsonObject("links");
            if (links.has("clone") && links.get("clone").isJsonArray()) {
                JsonArray cloneLinks = links.getAsJsonArray("clone");
                for (int i = 0; i < cloneLinks.size(); i++) {
                    JsonObject link = cloneLinks.get(i).getAsJsonObject();
                    if (link.has("name") && "https".equals(link.get("name").getAsString())) {
                        return link.get("href").getAsString();
                    }
                }
                // Fallback: first clone link
                if (cloneLinks.size() > 0) {
                    return cloneLinks.get(0).getAsJsonObject().get("href").getAsString();
                }
            }
        }
        // Bitbucket Data Center / fallback: construct from repository info
        if (repository.has("slug") && !repository.get("slug").isJsonNull()) {
            String slug = repository.get("slug").getAsString();
            // Note: This requires base URL configuration; fallback to slug
            return "https://bitbucket.org/" + slug;
        }
        return "https://bitbucket.org/unknown";
    }
}

