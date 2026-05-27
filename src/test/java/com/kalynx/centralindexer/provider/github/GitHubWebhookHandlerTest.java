package com.kalynx.centralindexer.provider.github;

import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitHubWebhookHandler}.
 */
class GitHubWebhookHandlerTest {

    private static final String SECRET = "test-secret";
    private static final String REPO = "owner/my-repo";

    private List<ReviewEvent> submitted;
    private GitHubWebhookHandler handler;

    @BeforeEach
    void setUp() {
        submitted = new ArrayList<>();
        ProviderConfig config = new ProviderConfig("github", Collections.emptyList(),
                Map.of("webhookSecret", SECRET));
        EventSink sink = event -> submitted.add(event);
        handler = new GitHubWebhookHandler(config, sink);
    }

    @Test
    void handle_notesRefFirstOccurrence_emitsReviewCreated() throws Exception {
        byte[] body = buildPushBody(REPO, "refs/notes/reviews/rev-001/metadata/title",
                "abc123", "jane.doe", "2026-05-19T10:00:00Z");
        handler.handle(buildHeaders(body, "push", "delivery-1"), body);

        assertEquals(1, submitted.size());
        ReviewEvent event = submitted.get(0);
        assertEquals(EventType.REVIEW_CREATED, event.eventType());
        assertEquals("rev-001", event.reviewId());
        assertEquals(REPO, event.repository());
        assertEquals("jane.doe", event.actorUser());
        assertEquals("delivery-1", event.deliveryId());
    }

    @Test
    void handle_notesRefSecondOccurrence_emitsReviewUpdated() throws Exception {
        byte[] body = buildPushBody(REPO, "refs/notes/reviews/rev-002/metadata/title",
                "abc123", "jane.doe", "2026-05-19T10:00:00Z");
        handler.handle(buildHeaders(body, "push", "delivery-1"), body);
        handler.handle(buildHeaders(body, "push", "delivery-2"), body);

        assertEquals(2, submitted.size());
        assertEquals(EventType.REVIEW_CREATED, submitted.get(0).eventType());
        assertEquals(EventType.REVIEW_UPDATED, submitted.get(1).eventType());
    }

    @Test
    void handle_commentTextRef_emitsReviewCommentAdded() throws Exception {
        byte[] body = buildPushBody(REPO, "refs/notes/reviews/rev-003/comments/1/text",
                "abc123", "bob", "2026-05-19T10:00:00Z");
        handler.handle(buildHeaders(body, "push", "delivery-3"), body);

        assertEquals(1, submitted.size());
        assertEquals(EventType.REVIEW_COMMENT_ADDED, submitted.get(0).eventType());
    }

    @Test
    void handle_branchHeadRef_emitsBranchUpdated() throws Exception {
        byte[] body = buildPushBody(REPO, "refs/heads/main",
                "deadbeef1234", "jane.doe", "2026-05-19T10:00:00Z");
        handler.handle(buildHeaders(body, "push", "delivery-4"), body);

        assertEquals(1, submitted.size());
        ReviewEvent event = submitted.get(0);
        assertEquals(EventType.BRANCH_UPDATED, event.eventType());
        assertNull(event.reviewId());
        assertEquals("main", event.payload().get("branch_name"));
        assertEquals("deadbeef1234", event.payload().get("head_commit"));
        assertNotNull(event.payload().get("repository_url"));
    }

    @Test
    void handle_branchDeleted_emitsBranchDeleted() throws Exception {
        byte[] body = buildDeleteBody(REPO, "refs/heads/feature-x", "jane.doe");
        handler.handle(buildHeaders(body, "push", "delivery-5"), body);

        assertEquals(1, submitted.size());
        assertEquals(EventType.BRANCH_DELETED, submitted.get(0).eventType());
    }

    @Test
    void handle_invalidSignature_discardsEvent() throws Exception {
        byte[] body = buildPushBody(REPO, "refs/notes/reviews/rev-bad/metadata/title",
                "abc123", "jane.doe", "2026-05-19T10:00:00Z");
        Map<String, String> headers = Map.of(
                "x-github-event", "push",
                "x-github-delivery", "delivery-bad",
                "x-hub-signature-256", "sha256=invalidsignature");
        handler.handle(headers, body);
        assertTrue(submitted.isEmpty(), "Invalid signature must not submit any event");
    }

    @Test
    void handle_nonPushEvent_discardsEvent() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        handler.handle(Map.of("x-github-event", "pull_request"), body);
        assertTrue(submitted.isEmpty());
    }

    @Test
    void handle_unrecognisedRef_discardsEvent() throws Exception {
        byte[] body = buildPushBody(REPO, "refs/tags/v1.0", "abc123", "jane.doe",
                "2026-05-19T10:00:00Z");
        handler.handle(buildHeaders(body, "push", "delivery-tag"), body);
        assertTrue(submitted.isEmpty(), "Tag refs must be silently discarded");
    }

    private Map<String, String> buildHeaders(byte[] body, String event, String deliveryId)
            throws Exception {
        String sig = "sha256=" + computeHmac(SECRET, body);
        return Map.of(
                "x-github-event", event,
                "x-github-delivery", deliveryId,
                "x-hub-signature-256", sig);
    }

    private byte[] buildPushBody(String repo, String ref, String afterHash,
                                  String pusher, String timestamp) {
        return ("{\"ref\":\"" + ref + "\","
                + "\"after\":\"" + afterHash + "\","
                + "\"repository\":{\"full_name\":\"" + repo + "\",\"name\":\"my-repo\"},"
                + "\"pusher\":{\"name\":\"" + pusher + "\"},"
                + "\"head_commit\":{\"id\":\"" + afterHash + "\",\"timestamp\":\"" + timestamp + "\"}}"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildDeleteBody(String repo, String ref, String pusher) {
        return ("{\"ref\":\"" + ref + "\","
                + "\"after\":\"0000000000000000000000000000000000000000\","
                + "\"repository\":{\"full_name\":\"" + repo + "\",\"name\":\"my-repo\"},"
                + "\"pusher\":{\"name\":\"" + pusher + "\"}}"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private String computeHmac(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

