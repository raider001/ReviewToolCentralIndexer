package com.kalynx.centralindexer.db;

/**
 * Immutable projection of one row from the {@code repositories} table.
 *
 * @param owner            the repository owner (GitHub organisation or user)
 * @param repository       the repository name
 * @param url              the canonical clone URL
 * @param kalynxReviewHead the last-known HEAD commit SHA of {@code refs/heads/kalynx-reviews},
 *                         or {@code null} if the cursor has not been initialised yet
 */
public record RepositoryRecord(String owner, String repository, String url, String kalynxReviewHead) {}
