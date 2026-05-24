package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Performs commit-based startup reconciliation for all tracked repositories.
 *
 * <p>For each repository in the {@code repositories} table, the reconciler compares
 * the stored {@code kalynx_review_head} cursor against the live HEAD of
 * {@code refs/heads/kalynx-reviews} returned by the provider plugin. When they differ,
 * the plugin replays the missed commits as {@link com.kalynx.centralindexer.model.ReviewEvent}s
 * via its {@link com.kalynx.centralindexer.spi.EventSink}, then the cursor is advanced to
 * the new HEAD.
 *
 * <p>This closes the event gap caused by server crashes, OOM kills, and plugin failures —
 * all of which result in a process restart that triggers this reconciler. See
 * {@code Documentation/Design/recovery.md} scenarios 1, 3 (path E), 5, and 12.
 *
 * <p>The cursor is updated only after all events in the range have been submitted, so a
 * crash mid-reconciliation causes the next startup to re-reconcile the same range
 * (idempotent, since upserts gate on {@code last_updated}).
 *
 * <p>If the plugin returns {@code null} from
 * {@link ProviderPlugin#fetchKalynxReviewHead}, the repository is skipped — either the
 * branch does not exist yet or the plugin does not support commit-based reconciliation.
 */
public final class StartupReconciler {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciler.class);

    private final RepositoriesRepository repositoriesRepository;
    private final BranchRepository branchRepository;
    private final ProviderPlugin plugin;

    public StartupReconciler(RepositoriesRepository repositoriesRepository,
                              BranchRepository branchRepository, ProviderPlugin plugin) {
        this.repositoriesRepository = repositoriesRepository;
        this.branchRepository = branchRepository;
        this.plugin = plugin;
    }

    /**
     * Runs the reconciliation pass for all tracked repositories.
     *
     * <p>Per-repository failures are logged and skipped so that a single unreachable
     * repository does not block reconciliation for the others.
     *
     * @throws SQLException         if reading the repositories table fails
     * @throws InterruptedException if the thread is interrupted during a DB operation
     */
    public void run() throws SQLException, InterruptedException {
        List<RepositoryRecord> repos = repositoriesRepository.findAll();
        if (repos.isEmpty()) {
            log.debug("No repositories registered — skipping startup reconciliation");
            return;
        }
        log.info("Starting startup reconciliation for {} repository/repositories", repos.size());
        for (RepositoryRecord repo : repos) {
            reconcileRepository(repo);
        }
        log.info("Startup reconciliation complete");
    }

    public void reconcileRepository(RepositoryRecord repo) {
        String repoPath = repo.owner() + "/" + repo.repository();

        // Always refresh all branches on startup (idempotent upserts).
        try {
            plugin.reconcileAllBranches(repoPath);
        } catch (Exception e) {
            log.warn("Branch reconciliation failed for {} — continuing with review reconciliation: {}",
                    repoPath, e.getMessage());
        }

        // Commit-based review reconciliation — read kalynx-reviews HEAD from DB (written by reconcileAllBranches).
        try {
            Optional<String> liveHeadOpt = branchRepository.findHeadCommit(
                    repo.owner(), repo.repository(), "kalynx-reviews");
            if (liveHeadOpt.isEmpty()) {
                log.debug("{}: kalynx-reviews branch not found — review reconciliation skipped", repoPath);
                return;
            }
            String liveHead = liveHeadOpt.get();

            String storedHead = repo.kalynxReviewHead();
            if (storedHead == null) {
                // First run — index the full tree then record the cursor.
                log.info("{}: no prior cursor — indexing full kalynx-reviews tree at {}",
                        repoPath, abbrev(liveHead));
                boolean ok = plugin.reconcileFullReviewTree(repoPath, liveHead);
                if (ok) {
                    repositoriesRepository.updateKalynxReviewHead(repo.owner(), repo.repository(), liveHead);
                    log.info("{}: full tree indexed, cursor set to {}", repoPath, abbrev(liveHead));
                } else {
                    log.warn("{}: full tree reconciliation failed — cursor not advanced; will retry on next startup",
                            repoPath);
                }
                return;
            }

            if (liveHead.equals(storedHead)) {
                log.debug("{} reviews up to date (head={})", repoPath, abbrev(storedHead));
                return;
            }

            log.info("Gap detected for {}: stored={} live={} — reconciling",
                    repoPath, abbrev(storedHead), abbrev(liveHead));
            boolean ok = plugin.reconcileFromCommit(repoPath, storedHead, liveHead);
            if (ok) {
                repositoriesRepository.updateKalynxReviewHead(repo.owner(), repo.repository(), liveHead);
                log.info("Review reconciliation complete for {} (cursor → {})", repoPath, abbrev(liveHead));
            } else {
                log.warn("Review reconciliation failed for {} — cursor not advanced; will retry on next startup",
                        repoPath);
            }

        } catch (SQLException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("DB error during review reconciliation for {}: {}", repoPath, e.getMessage());
        } catch (Exception e) {
            log.warn("Review reconciliation failed for {}: {}", repoPath, e.getMessage());
        }
    }

    private static String abbrev(String sha) {
        return sha != null && sha.length() >= 7 ? sha.substring(0, 7) : sha;
    }
}
