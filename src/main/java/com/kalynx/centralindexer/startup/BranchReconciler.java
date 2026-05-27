package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Reconciles branch heads for all tracked repositories on startup.
 *
 * <p>For each repository in the {@code repositories} table, calls
 * {@link ProviderPlugin#reconcileAllBranches} to refresh the {@code branches} table.
 * Per-repository failures are logged and skipped so that a single unreachable repository
 * does not block reconciliation for the others.
 */
final class BranchReconciler implements Reconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(BranchReconciler.class);

    private final RepositoriesRepository repositoriesRepository;
    private final ProviderPlugin plugin;

    BranchReconciler(RepositoriesRepository repositoriesRepository, ProviderPlugin plugin) {
        this.repositoriesRepository = repositoriesRepository;
        this.plugin = plugin;
    }

    @Override
    public void reconcile() {
        List<RepositoryRecord> repos;
        try {
            repos = repositoriesRepository.findAll();
        } catch (Exception e) {
            LOGGER.warn("Failed to load repositories for branch reconciliation: {}", e.getMessage());
            return;
        }
        for (RepositoryRecord repo : repos) {
            reconcileOne(repo);
        }
    }

    void reconcileOne(RepositoryRecord repo) {
        String repoPath = repo.owner() + "/" + repo.repository();
        try {
            plugin.reconcileAllBranches(repoPath);
        } catch (Exception e) {
            LOGGER.warn("Branch reconciliation failed for {} — continuing with review reconciliation: {}",
                    repoPath, e.getMessage());
        }
    }
}
