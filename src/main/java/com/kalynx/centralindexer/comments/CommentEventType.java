package com.kalynx.centralindexer.comments;

/** Classifies a comment change detected on a {@code kalynx-reviews} push. */
public enum CommentEventType {
    /** New comment created or a reply/text appended. */
    ADDED,
    /** Resolution state changed ({@code status} sub-stream written). */
    UPDATED
}
