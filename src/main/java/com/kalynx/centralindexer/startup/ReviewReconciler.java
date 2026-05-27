package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.List;

/**
 * Reconciles the {@code kalynx-reviews} orphan branch for all tracked repositories on startup.
 *
 * <p>For each repository, fetches the live HEAD from the provider plugin and replays any
 * missed commits since the stored cursor. Safe to call outside the startup sequence via
 * {@link #reconcileOne} — used by the live-update path when a {@code kalynx-reviews} push
 * webhook arrives.
 */
final class ReviewReconciler implements Reconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewReconciler.class);

    private final RepositoriesRepository repositoriesRepository;
    private final ProviderPlugin plugin;

    ReviewReconciler(RepositoriesRepository repositoriesRepository, ProviderPlugin plugin) {
        this.repositoriesRepository = repositoriesRepository;
        this.plugin = plugin;
    }

    @Override
    public void reconcile() {
        List<RepositoryRecord> repos;
        try {
            repos = repositoriesRepository.findAll();
        } catch (Exception e) {
            LOGGER.warn("Failed to load repositories for review reconciliation: {}", e.getMessage());
            return;
        }
        for (RepositoryRecord repo : repos) {
            reconcileOne(repo);
        }
    }

    void reconcileOne(RepositoryRecord repo) {
        String repoPath = repo.owner() + "/" + repo.repository();
        try {
            String liveHead = plugin.fetchKalynxReviewHead(repoPath);
            if (liveHead == null) {
                LOGGER.debug("{}: kalynx-reviews branch not found or plugin does not support fetch " +
                        "— review reconciliation skipped", repoPath);
                return;
            }

            String storedHead = repo.kalynxReviewHead();
            if (storedHead == null) {
                LOGGER.info("{}: no prior cursor — indexing full kalynx-reviews tree at {}",
                        repoPath, abbrev(liveHead));
                boolean ok = plugin.reconcileFullReviewTree(repoPath, liveHead);
                if (ok) {
                    repositoriesRepository.updateKalynxReviewHead(repo.owner(), repo.repository(), liveHead);
                    LOGGER.info("{}: full tree indexed, cursor set to {}", repoPath, abbrev(liveHead));
                } else {
                    LOGGER.warn("{}: full tree reconciliation failed — cursor not advanced; will retry on next startup",
                            repoPath);
                }
                return;
            }

            if (liveHead.equals(storedHead)) {
                LOGGER.debug("{} reviews up to date (head={})", repoPath, abbrev(storedHead));
                return;
            }

            LOGGER.info("Gap detected for {}: stored={} live={} — reconciling",
                    repoPath, abbrev(storedHead), abbrev(liveHead));
            boolean ok = plugin.reconcileFromCommit(repoPath, storedHead, liveHead);
            if (ok) {
                repositoriesRepository.updateKalynxReviewHead(repo.owner(), repo.repository(), liveHead);
                LOGGER.info("Review reconciliation complete for {} (cursor → {})", repoPath, abbrev(liveHead));
            } else {
                LOGGER.warn("Review reconciliation failed for {} — cursor not advanced; will retry on next startup",
                        repoPath);
            }

        } catch (SQLException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("DB error during review reconciliation for {}: {}", repoPath, e.getMessage());
        } catch (Exception e) {
            LOGGER.warn("Review reconciliation failed for {}: {}", repoPath, e.getMessage());
        }
    }

    private static String abbrev(String sha) {
        return sha != null && sha.length() >= 7 ? sha.substring(0, 7) : sha;
    }
}
