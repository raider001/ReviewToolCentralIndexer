package com.kalynx.centralindexer.backfill;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.ConfigLoader;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI entry point for the {@code backfill-branches} tool.
 *
 * <p>Usage:
 * <pre>
 *   java -cp indexer.jar com.kalynx.centralindexer.backfill.BackfillMain [options]
 *
 *   Options:
 *     --dry-run            Report what would change without modifying the database.
 *     --batch-size &lt;N&gt;     Process N review IDs per database round-trip (default: 100).
 * </pre>
 *
 * <p>Exits with status {@code 0} on success, {@code 1} if conflicts are detected or if
 * a fatal error occurs.
 */
public final class BackfillMain {

    private static final Logger log = LoggerFactory.getLogger(BackfillMain.class);

    private BackfillMain() {
    }

    /**
     * Backfill entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        boolean dryRun = false;
        int batchSize = BackfillOptions.DEFAULT_BATCH_SIZE;

        for (int i = 0; i < args.length; i++) {
            if ("--dry-run".equals(args[i])) {
                dryRun = true;
            } else if ("--batch-size".equals(args[i]) && i + 1 < args.length) {
                try {
                    batchSize = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid --batch-size value: " + args[i]);
                    System.exit(1);
                }
            }
        }

        BackfillOptions options = new BackfillOptions(dryRun, batchSize);

        try {
            AppConfig config = ConfigLoader.load();
            ConnectionPool pool = new ConnectionPool(config.getDatabase());
            new DatabaseInitializer(pool).init();

            BackfillReport report = new BackfillBranchesTool(pool).run(options);
            pool.close();

            System.out.printf("Backfill %s: total=%d updated=%d skipped=%d conflicts=%d%n",
                    dryRun ? "(dry-run)" : "complete",
                    report.totalReviews(),
                    report.updatedReviews(),
                    report.skipped(),
                    report.conflictReviewIds().size());

            if (report.hasConflicts()) {
                System.err.println("Unresolvable branch references detected for review IDs:");
                report.conflictReviewIds().forEach(id -> System.err.println("  " + id));
                System.exit(1);
            }
        } catch (Exception e) {
            log.error("Backfill failed", e);
            System.exit(1);
        }
    }
}
