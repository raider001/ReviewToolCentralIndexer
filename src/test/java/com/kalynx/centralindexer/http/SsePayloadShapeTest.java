package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the SSE event name and data shape for comment events.
 */
class SsePayloadShapeTest {

    private final SseHandler handler = new SseHandler(null, null);

    // ── toSseName ────────────────────────────────────────────────────────────

    @Test
    void toSseName_reviewCommentAdded_mapsToCommentAdded() {
        assertEquals("comment.added", SseHandler.toSseName(EventType.REVIEW_COMMENT_ADDED));
    }

    @Test
    void toSseName_reviewCommentUpdated_mapsToCommentUpdated() {
        assertEquals("comment.updated", SseHandler.toSseName(EventType.REVIEW_COMMENT_UPDATED));
    }

    @Test
    void toSseName_reviewCreated_returnsEnumName() {
        assertEquals("REVIEW_CREATED", SseHandler.toSseName(EventType.REVIEW_CREATED));
    }

    @Test
    void toSseName_reviewUpdated_returnsEnumName() {
        assertEquals("REVIEW_UPDATED", SseHandler.toSseName(EventType.REVIEW_UPDATED));
    }

    @Test
    void toSseName_branchUpdated_returnsEnumName() {
        assertEquals("BRANCH_UPDATED", SseHandler.toSseName(EventType.BRANCH_UPDATED));
    }

    // ── toSseData for comment events ─────────────────────────────────────────

    @Test
    void toSseData_commentAdded_payloadContainsType() {
        String data = handler.toSseData(buildCommentEvent(EventType.REVIEW_COMMENT_ADDED, "rev-abc",
                "https://git.example.com/r.git", "comment-uuid-001"));
        assertTrue(data.contains("\"type\":\"comment.added\""), "Payload must contain type");
    }

    @Test
    void toSseData_commentAdded_payloadContainsReviewId() {
        String data = handler.toSseData(buildCommentEvent(EventType.REVIEW_COMMENT_ADDED, "rev-abc",
                "https://git.example.com/r.git", "comment-uuid-001"));
        assertTrue(data.contains("\"review_id\":\"rev-abc\""), "Payload must contain review_id");
    }

    @Test
    void toSseData_commentAdded_payloadContainsCommentId() {
        String data = handler.toSseData(buildCommentEvent(EventType.REVIEW_COMMENT_ADDED, "rev-abc",
                "https://git.example.com/r.git", "comment-uuid-001"));
        assertTrue(data.contains("\"comment_id\":\"comment-uuid-001\""), "Payload must contain comment_id");
    }

    @Test
    void toSseData_commentAdded_payloadContainsRepositoryUrl() {
        String data = handler.toSseData(buildCommentEvent(EventType.REVIEW_COMMENT_ADDED, "rev-abc",
                "https://git.example.com/r.git", "comment-uuid-001"));
        assertTrue(data.contains("\"repository_url\":\"https://git.example.com/r.git\""),
                "Payload must contain repository_url");
    }

    @Test
    void toSseData_commentUpdated_payloadContainsType() {
        String data = handler.toSseData(buildCommentEvent(EventType.REVIEW_COMMENT_UPDATED, "rev-xyz",
                "https://git.example.com/r.git", "comment-uuid-002"));
        assertTrue(data.contains("\"type\":\"comment.updated\""), "Payload must contain type");
    }

    @Test
    void toSseData_commentEvent_payloadUnder1KB() {
        String data = handler.toSseData(buildCommentEvent(
                EventType.REVIEW_COMMENT_ADDED,
                "rev-" + "a".repeat(50),
                "https://git.example.com/" + "x".repeat(100) + ".git",
                "comment-uuid-" + "0".repeat(36)));
        assertTrue(data.getBytes(StandardCharsets.UTF_8).length < 1024,
                "Comment SSE payload must be under 1 KB");
    }

    // ── non-comment events still serialize as full ReviewEvent JSON ──────────

    @Test
    void toSseData_reviewCreated_containsEventType() {
        ReviewEvent event = new ReviewEvent(1L, Instant.parse("2026-05-26T10:00:00Z"),
                "alice/repo", EventType.REVIEW_CREATED, "rev-1", null, null, Map.of());
        String data = handler.toSseData(event);
        assertTrue(data.contains("REVIEW_CREATED"), "Full event JSON must contain eventType");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static ReviewEvent buildCommentEvent(EventType type, String reviewId,
                                                  String repoUrl, String commentId) {
        return new ReviewEvent(0L, Instant.parse("2026-05-26T10:00:00Z"),
                "alice/repo", type, reviewId, null, null,
                Map.of("repository_url", repoUrl, "comment_id", commentId));
    }
}
