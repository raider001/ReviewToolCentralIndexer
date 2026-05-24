package com.kalynx.centralindexer.db;

/**
 * Immutable representation of a single row from the {@code branches} table,
 * used as the internal result type for {@link BranchRepository} queries.
 */
public record BranchRecord(String owner, String repository, String branchName) {}
