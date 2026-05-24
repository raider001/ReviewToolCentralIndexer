package com.kalynx.centralindexer.http;

import com.google.gson.Gson;
import com.kalynx.centralindexer.json.GsonFactory;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests asserting the shape and size of SSE event payloads.
 *
 * <p>Ensures that:
 * <ul>
 *   <li>{@code BRANCH_UPDATED} frames include {@code repository_url}, {@code branch_name},
 *       and {@code head_commit} routing keys</li>
 *   <li>{@code BRANCH_DELETED} frames include {@code repository_url} and {@code branch_name}</li>
 *   <li>No representative frame exceeds the 1 KB payload size gate</li>
 * </ul>
 */
class SsePayloadShapeTest {

    private static final int MAX_PAYLOAD_BYTES = 1024;
    private final Gson gson = GsonFactory.getInstance();

    @Test
    void branchUpdatedPayloadContainsRepositoryUrl() {
        String json = serializeEvent(branchUpdatedEvent());
        assertTrue(json.contains("\"repository_url\""),
                "BRANCH_UPDATED must include repository_url");
    }

    @Test
    void branchUpdatedPayloadContainsBranchName() {
        String json = serializeEvent(branchUpdatedEvent());
        assertTrue(json.contains("\"branch_name\""),
                "BRANCH_UPDATED must include branch_name");
    }

    @Test
    void branchUpdatedPayloadContainsHeadCommit() {
        String json = serializeEvent(branchUpdatedEvent());
        assertTrue(json.contains("\"head_commit\""),
                "BRANCH_UPDATED must include head_commit");
    }

    @Test
    void branchDeletedPayloadContainsRepositoryUrl() {
        String json = serializeEvent(branchDeletedEvent());
        assertTrue(json.contains("\"repository_url\""),
                "BRANCH_DELETED must include repository_url");
    }

    @Test
    void branchDeletedPayloadContainsBranchName() {
        String json = serializeEvent(branchDeletedEvent());
        assertTrue(json.contains("\"branch_name\""),
                "BRANCH_DELETED must include branch_name");
    }

    @Test
    void branchUpdatedSseFrameUnder1KB() {
        String frame = buildSseFrame(branchUpdatedEvent());
        int bytes = frame.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes < MAX_PAYLOAD_BYTES,
                "BRANCH_UPDATED SSE frame must be < 1 KB but was " + bytes + " bytes");
    }

    @Test
    void branchDeletedSseFrameUnder1KB() {
        String frame = buildSseFrame(branchDeletedEvent());
        int bytes = frame.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes < MAX_PAYLOAD_BYTES,
                "BRANCH_DELETED SSE frame must be < 1 KB but was " + bytes + " bytes");
    }

    @Test
    void reviewCreatedSseFrameUnder1KB() {
        String frame = buildSseFrame(reviewEvent(EventType.REVIEW_CREATED));
        int bytes = frame.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes < MAX_PAYLOAD_BYTES,
                "REVIEW_CREATED SSE frame must be < 1 KB but was " + bytes + " bytes");
    }

    @Test
    void reviewUpdatedSseFrameUnder1KB() {
        String frame = buildSseFrame(reviewEvent(EventType.REVIEW_UPDATED));
        int bytes = frame.getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes < MAX_PAYLOAD_BYTES,
                "REVIEW_UPDATED SSE frame must be < 1 KB but was " + bytes + " bytes");
    }

    private ReviewEvent branchUpdatedEvent() {
        return new ReviewEvent(
                0L,
                Instant.parse("2026-05-20T10:00:00Z"),
                "alice/repo",
                EventType.BRANCH_UPDATED,
                null,
                "alice",
                "delivery-abc123",
                Map.of(
                        "repository_url", "https://github.com/alice/repo.git",
                        "branch_name", "feature/new-thing",
                        "head_commit", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"));
    }

    private ReviewEvent branchDeletedEvent() {
        return new ReviewEvent(
                0L,
                Instant.parse("2026-05-20T10:00:00Z"),
                "alice/repo",
                EventType.BRANCH_DELETED,
                null,
                "alice",
                "delivery-def456",
                Map.of(
                        "repository_url", "https://github.com/alice/repo.git",
                        "branch_name", "feature/old-thing"));
    }

    private ReviewEvent reviewEvent(EventType type) {
        return new ReviewEvent(
                0L,
                Instant.parse("2026-05-20T10:00:00Z"),
                "alice/repo",
                type,
                "review-xyz-789",
                "alice",
                "delivery-ghi789",
                Map.of());
    }

    private String serializeEvent(ReviewEvent event) {
        return gson.toJson(event);
    }

    private String buildSseFrame(ReviewEvent event) {
        return "event: " + event.eventType().name() + "\n"
                + "data: " + gson.toJson(event) + "\n\n";
    }
}
