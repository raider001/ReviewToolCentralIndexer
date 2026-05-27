package com.kalynx.centralindexer.comments;

/** A single comment change extracted from a {@code kalynx-reviews} commit diff. */
public record CommentChange(String reviewId, String commentId, CommentEventType eventType) {}
