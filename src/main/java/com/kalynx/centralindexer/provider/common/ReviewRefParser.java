package com.kalynx.centralindexer.provider.common;

import com.kalynx.centralindexer.model.EventType;

/**
 * Parses git ref strings used by the review tool and maps them to canonical
 * {@link EventType} values.
 *
 * <p>Two ref families are recognised:
 * <ul>
 *   <li>{@code refs/notes/reviews/{reviewId}/{streamName}} — review note changes</li>
 *   <li>{@code refs/heads/{branch}} — standard branch pushes</li>
 * </ul>
 *
 * <p>Any other ref returns {@code null} from {@link #parse(String)} and should be
 * silently discarded by the caller.
 */
public final class ReviewRefParser {

    private static final String NOTES_PREFIX = "refs/notes/reviews/";
    private static final String HEADS_PREFIX = "refs/heads/";
    private static final String ALL_ZEROS = "0000000000000000000000000000000000000000";

    private ReviewRefParser() {
    }

    /**
     * Parses a git ref string into a structured {@link ParsedRef}.
     *
     * @param ref the full git ref string; must not be {@code null}
     * @return the parsed result, or {@code null} if the ref does not match any known pattern
     */
    public static ParsedRef parse(String ref) {
        if (ref.startsWith(NOTES_PREFIX)) {
            String remainder = ref.substring(NOTES_PREFIX.length());
            int slash = remainder.indexOf('/');
            if (slash < 0) {
                return null;
            }
            String reviewId = remainder.substring(0, slash);
            String streamName = remainder.substring(slash + 1);
            return new ParsedRef(RefType.NOTES, reviewId, streamName, null);
        }
        if (ref.startsWith(HEADS_PREFIX)) {
            String branch = ref.substring(HEADS_PREFIX.length());
            return new ParsedRef(RefType.HEADS, null, null, branch);
        }
        return null;
    }

    /**
     * Maps a parsed notes ref to an {@link EventType}.
     *
     * @param streamName    the stream segment after the reviewId (e.g. {@code "metadata/title"})
     * @param isFirstOccurrence {@code true} if no prior event exists for this reviewId in
     *                      the current session; used to distinguish
     *                      {@link EventType#REVIEW_CREATED} from {@link EventType#REVIEW_UPDATED}
     * @return the appropriate event type; never {@code null}
     */
    public static EventType mapNotesEventType(String streamName, boolean isFirstOccurrence) {
        if ("metadata/title".equals(streamName) && isFirstOccurrence) {
            return EventType.REVIEW_CREATED;
        }
        if (streamName.startsWith("comments/") && streamName.endsWith("/text")) {
            return EventType.REVIEW_COMMENT_ADDED;
        }
        if (streamName.startsWith("comments/") && streamName.endsWith("/status")) {
            return EventType.REVIEW_COMMENT_UPDATED;
        }
        return EventType.REVIEW_UPDATED;
    }

    /**
     * Returns {@code true} if the supplied commit hash represents a branch deletion
     * (i.e. the hash is all zeros, as used by GitHub and GitLab).
     *
     * @param afterHash the commit hash that the branch now points to
     * @return {@code true} if the branch was deleted
     */
    public static boolean isBranchDeletion(String afterHash) {
        return afterHash == null || ALL_ZEROS.equals(afterHash);
    }

    /**
     * The family of ref that was matched.
     */
    public enum RefType {
        /** A {@code refs/notes/reviews/*} ref — carries review data. */
        NOTES,
        /** A {@code refs/heads/*} ref — a standard branch. */
        HEADS
    }

    /**
     * The result of parsing a single git ref string.
     *
     * @param type       the ref family
     * @param reviewId   the review UUID; non-null only when {@code type == NOTES}
     * @param streamName the stream segment after the reviewId; non-null only when {@code type == NOTES}
     * @param branch     the bare branch name; non-null only when {@code type == HEADS}
     */
    public record ParsedRef(RefType type, String reviewId, String streamName, String branch) {
    }
}

