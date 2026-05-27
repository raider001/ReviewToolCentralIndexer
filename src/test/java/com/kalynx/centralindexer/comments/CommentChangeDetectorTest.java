package com.kalynx.centralindexer.comments;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CommentChangeDetector}.
 */
class CommentChangeDetectorTest {

    // ── classification by sub-stream ─────────────────────────────────────────

    @Test
    void detect_metadataPath_classifiedAsAdded() {
        List<CommentChange> changes = CommentChangeDetector.detect(List.of(
                "reviews/rev-1/comments/comment-uuid-a/metadata"));

        assertEquals(1, changes.size());
        assertEquals(CommentEventType.ADDED, changes.get(0).eventType());
        assertEquals("rev-1", changes.get(0).reviewId());
        assertEquals("comment-uuid-a", changes.get(0).commentId());
    }

    @Test
    void detect_textPath_classifiedAsAdded() {
        List<CommentChange> changes = CommentChangeDetector.detect(List.of(
                "reviews/rev-2/comments/comment-uuid-b/text"));

        assertEquals(1, changes.size());
        assertEquals(CommentEventType.ADDED, changes.get(0).eventType());
    }

    @Test
    void detect_statusPath_classifiedAsUpdated() {
        List<CommentChange> changes = CommentChangeDetector.detect(List.of(
                "reviews/rev-3/comments/comment-uuid-c/status"));

        assertEquals(1, changes.size());
        assertEquals(CommentEventType.UPDATED, changes.get(0).eventType());
    }

    // ── precedence: ADDED wins when same commentId has both sub-streams ──────

    @Test
    void detect_metadataAndStatusForSameComment_addedWins() {
        List<CommentChange> changes = CommentChangeDetector.detect(List.of(
                "reviews/rev-4/comments/comment-uuid-d/metadata",
                "reviews/rev-4/comments/comment-uuid-d/status"));

        assertEquals(1, changes.size());
        assertEquals(CommentEventType.ADDED, changes.get(0).eventType());
    }

    @Test
    void detect_textAndStatusForSameComment_addedWins() {
        List<CommentChange> changes = CommentChangeDetector.detect(List.of(
                "reviews/rev-5/comments/comment-uuid-e/text",
                "reviews/rev-5/comments/comment-uuid-e/status"));

        assertEquals(1, changes.size());
        assertEquals(CommentEventType.ADDED, changes.get(0).eventType());
    }

    // ── multiple comments and reviews in one commit ──────────────────────────

    @Test
    void detect_multipleCommentsInSameReview_returnsAllChanges() {
        List<CommentChange> changes = CommentChangeDetector.detect(List.of(
                "reviews/rev-6/comments/comment-uuid-f/metadata",
                "reviews/rev-6/comments/comment-uuid-g/status"));

        assertEquals(2, changes.size());
        Map<String, CommentEventType> byId = changes.stream()
                .collect(Collectors.toMap(CommentChange::commentId, CommentChange::eventType));
        assertEquals(CommentEventType.ADDED,   byId.get("comment-uuid-f"));
        assertEquals(CommentEventType.UPDATED, byId.get("comment-uuid-g"));
    }

    @Test
    void detect_commentsAcrossMultipleReviews_returnsAllChanges() {
        List<CommentChange> changes = CommentChangeDetector.detect(List.of(
                "reviews/rev-7/comments/comment-uuid-h/text",
                "reviews/rev-8/comments/comment-uuid-i/status"));

        assertEquals(2, changes.size());
        assertEquals("rev-7", changes.get(0).reviewId());
        assertEquals("rev-8", changes.get(1).reviewId());
    }

    // ── unrelated paths are ignored ──────────────────────────────────────────

    @Test
    void detect_nonCommentPaths_ignored() {
        List<CommentChange> changes = CommentChangeDetector.detect(List.of(
                "reviews/rev-9/metadata/status",
                "reviews/rev-9/branches",
                "some-other-file.txt",
                "reviews/rev-9/comments/comment-uuid-j/metadata"));

        assertEquals(1, changes.size());
        assertEquals("comment-uuid-j", changes.get(0).commentId());
    }

    @Test
    void detect_emptyInput_returnsEmptyList() {
        assertTrue(CommentChangeDetector.detect(List.of()).isEmpty());
    }

    @Test
    void detect_allUnrelatedPaths_returnsEmptyList() {
        assertTrue(CommentChangeDetector.detect(List.of(
                "reviews/rev-10/metadata/status",
                "refs/heads/kalynx-reviews")).isEmpty());
    }

    // ── deduplication: same commentId mentioned multiple times ───────────────

    @Test
    void detect_duplicateMetadataPath_producesOneChange() {
        List<CommentChange> changes = CommentChangeDetector.detect(List.of(
                "reviews/rev-11/comments/comment-uuid-k/metadata",
                "reviews/rev-11/comments/comment-uuid-k/metadata"));

        assertEquals(1, changes.size());
        assertEquals(CommentEventType.ADDED, changes.get(0).eventType());
    }
}
