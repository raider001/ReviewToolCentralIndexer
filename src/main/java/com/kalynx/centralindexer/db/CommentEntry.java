package com.kalynx.centralindexer.db;

import java.time.Instant;

/**
 * One row from {@code comments_index} joined with {@code repositories}, used as the
 * result type for {@link CommentsIndexRepository#findByReviewId}.
 */
public record CommentEntry(String commentId, String repositoryUrl, Instant lastUpdated) {}
