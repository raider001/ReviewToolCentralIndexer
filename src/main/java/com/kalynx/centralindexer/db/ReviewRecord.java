package com.kalynx.centralindexer.db;

import java.time.Instant;

/**
 * Immutable representation of a single row from the {@code reviews_index} table,
 * used as the internal result type for {@link ReviewsIndexRepository} queries.
 */
public record ReviewRecord(String reviewId, String status, Instant lastUpdated, String repositoriesJson) {}
