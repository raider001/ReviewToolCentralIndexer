package com.kalynx.centralindexer.backfill;

/**
 * Options controlling a {@link BackfillBranchesTool} run.
 *
 * @param dryRun    when {@code true}, compute what would change but do not write to the DB
 * @param batchSize number of review IDs processed per DB round-trip; must be &gt; 0
 */
public record BackfillOptions(boolean dryRun, int batchSize) {

    public static final int DEFAULT_BATCH_SIZE = 100;

    public BackfillOptions {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be > 0");
    }

    /** Full run processing all reviews in batches of {@value DEFAULT_BATCH_SIZE}. */
    public static BackfillOptions asFullRun() {
        return new BackfillOptions(false, DEFAULT_BATCH_SIZE);
    }

    /** Dry-run: report what would change without writing anything. */
    public static BackfillOptions asDryRun() {
        return new BackfillOptions(true, DEFAULT_BATCH_SIZE);
    }

    /** Full run with an explicit batch size. */
    public static BackfillOptions asFullRunBatch(int batchSize) {
        return new BackfillOptions(false, batchSize);
    }
}
