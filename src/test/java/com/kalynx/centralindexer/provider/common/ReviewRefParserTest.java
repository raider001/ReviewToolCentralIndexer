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
    void parse_notesRef_returnsNotesType() {
        ParsedRef result = ReviewRefParser.parse(
                "refs/notes/reviews/abc-uuid/metadata/status");
        assertNotNull(result);
        assertEquals(RefType.NOTES, result.type());
        assertEquals("abc-uuid", result.reviewId());
        assertEquals("metadata/status", result.streamName());
        assertNull(result.branch());
    }

    @Test
    void parse_headsRef_returnsHeadsType() {
        ParsedRef result = ReviewRefParser.parse("refs/heads/main");
        assertNotNull(result);
        assertEquals(RefType.HEADS, result.type());
        assertEquals("main", result.branch());
        assertNull(result.reviewId());
    }

    @Test
    void parse_unknownRef_returnsNull() {
        assertNull(ReviewRefParser.parse("refs/tags/v1.0"));
        assertNull(ReviewRefParser.parse("refs/other/something"));
    }

    @Test
    void parse_malformedNotesRef_returnsNull() {
        assertNull(ReviewRefParser.parse("refs/notes/reviews/no-stream-segment"));
    }

    @Test
    void mapNotesEventType_metadataTitleFirstOccurrence_returnsReviewCreated() {
        assertEquals(EventType.REVIEW_CREATED,
                ReviewRefParser.mapNotesEventType("metadata/title", true));
    }

    @Test
    void mapNotesEventType_metadataTitleSubsequent_returnsReviewUpdated() {
        assertEquals(EventType.REVIEW_UPDATED,
                ReviewRefParser.mapNotesEventType("metadata/title", false));
    }

    @Test
    void mapNotesEventType_metadataStatus_returnsReviewUpdated() {
        assertEquals(EventType.REVIEW_UPDATED,
                ReviewRefParser.mapNotesEventType("metadata/status", true));
    }

    @Test
    void mapNotesEventType_commentText_returnsReviewCommentAdded() {
        assertEquals(EventType.REVIEW_COMMENT_ADDED,
                ReviewRefParser.mapNotesEventType("comments/123/text", false));
    }

    @Test
    void mapNotesEventType_commentStatus_returnsReviewCommentUpdated() {
        assertEquals(EventType.REVIEW_COMMENT_UPDATED,
                ReviewRefParser.mapNotesEventType("comments/123/status", false));
    }

    @Test
    void mapNotesEventType_unknownStream_returnsReviewUpdated() {
        assertEquals(EventType.REVIEW_UPDATED,
                ReviewRefParser.mapNotesEventType("reviewers", false));
    }

    @Test
    void isBranchDeletion_zeroHashOrNull_returnsTrue() {
        assertTrue(ReviewRefParser.isBranchDeletion("0000000000000000000000000000000000000000"));
        assertTrue(ReviewRefParser.isBranchDeletion(null));
        assertFalse(ReviewRefParser.isBranchDeletion("abc123def456"));
    }
}

