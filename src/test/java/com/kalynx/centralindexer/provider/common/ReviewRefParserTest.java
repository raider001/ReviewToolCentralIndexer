package com.kalynx.centralindexer.provider.common;

import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.provider.common.ReviewRefParser.ParsedRef;
import com.kalynx.centralindexer.provider.common.ReviewRefParser.RefType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ReviewRefParser}.
 */
class ReviewRefParserTest {

    @Test
    void parsesNotesRef() {
        ParsedRef result = ReviewRefParser.parse(
                "refs/notes/reviews/abc-uuid/metadata/status");
        assertNotNull(result);
        assertEquals(RefType.NOTES, result.type());
        assertEquals("abc-uuid", result.reviewId());
        assertEquals("metadata/status", result.streamName());
        assertNull(result.branch());
    }

    @Test
    void parsesHeadsRef() {
        ParsedRef result = ReviewRefParser.parse("refs/heads/main");
        assertNotNull(result);
        assertEquals(RefType.HEADS, result.type());
        assertEquals("main", result.branch());
        assertNull(result.reviewId());
    }

    @Test
    void returnsNullForUnknownRef() {
        assertNull(ReviewRefParser.parse("refs/tags/v1.0"));
        assertNull(ReviewRefParser.parse("refs/other/something"));
    }

    @Test
    void returnsNullForMalformedNotesRef() {
        assertNull(ReviewRefParser.parse("refs/notes/reviews/no-stream-segment"));
    }

    @Test
    void mapsMetadataTitleFirstOccurrenceToReviewCreated() {
        assertEquals(EventType.REVIEW_CREATED,
                ReviewRefParser.mapNotesEventType("metadata/title", true));
    }

    @Test
    void mapsMetadataTitleSubsequentToReviewUpdated() {
        assertEquals(EventType.REVIEW_UPDATED,
                ReviewRefParser.mapNotesEventType("metadata/title", false));
    }

    @Test
    void mapsMetadataStatusToReviewUpdated() {
        assertEquals(EventType.REVIEW_UPDATED,
                ReviewRefParser.mapNotesEventType("metadata/status", true));
    }

    @Test
    void mapsCommentTextToReviewCommentAdded() {
        assertEquals(EventType.REVIEW_COMMENT_ADDED,
                ReviewRefParser.mapNotesEventType("comments/123/text", false));
    }

    @Test
    void mapsCommentStatusToReviewCommentUpdated() {
        assertEquals(EventType.REVIEW_COMMENT_UPDATED,
                ReviewRefParser.mapNotesEventType("comments/123/status", false));
    }

    @Test
    void mapsUnknownStreamToReviewUpdated() {
        assertEquals(EventType.REVIEW_UPDATED,
                ReviewRefParser.mapNotesEventType("reviewers", false));
    }

    @Test
    void detectsBranchDeletion() {
        assertTrue(ReviewRefParser.isBranchDeletion("0000000000000000000000000000000000000000"));
        assertTrue(ReviewRefParser.isBranchDeletion(null));
        assertFalse(ReviewRefParser.isBranchDeletion("abc123def456"));
    }
}

