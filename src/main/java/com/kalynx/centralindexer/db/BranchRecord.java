package com.kalynx.centralindexer.db;

/**
 * Immutable representation of a single row from the {@code branches} table joined with
 * {@code repositories}, used as the result type for {@link BranchRepository} queries.
 *
 * <p>{@code repositoryId} is the surrogate UUID from {@code repositories} — used
 * internally for cursor-based pagination. {@code owner} and {@code repository} are
 * resolved via the JOIN and are included for human-readable API responses.
 */
public record BranchRecord(String repositoryId, String owner, String repository, String branchName) {}
