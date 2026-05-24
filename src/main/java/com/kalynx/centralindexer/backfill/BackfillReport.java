package com.kalynx.centralindexer.backfill;

import java.util.List;

/**
 * Result of a {@link BackfillBranchesTool} run.
 *
 * @param totalReviews      distinct review IDs found in {@code review_branches}
 * @param updatedReviews    reviews whose {@code repositories} JSONB was (or would be) updated
 * @param skipped           reviews present in {@code review_branches} that produced no resolvable
 *                          branch data (broken FK references or missing rows)
 * @param conflictReviewIds review IDs whose branch or repository FK references could not be
 *                          resolved; subset of the skipped count
 */
public record BackfillReport(int totalReviews, int updatedReviews, int skipped,
                              List<String> conflictReviewIds) {

    public BackfillReport {
        conflictReviewIds = List.copyOf(conflictReviewIds);
    }

    /** Returns {@code true} if any unresolvable FK references were detected. */
    public boolean hasConflicts() {
        return !conflictReviewIds.isEmpty();
    }
}
