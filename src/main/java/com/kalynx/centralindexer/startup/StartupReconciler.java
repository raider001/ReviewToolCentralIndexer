package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Coordinates startup reconciliation by delegating to focused {@link Reconciler} instances.
 *
 * <p>Reconcilers run in order: branches first, then review refs. Per-reconciler failures
 * are handled internally — the coordinator does not propagate them.
 */
public final class StartupReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(StartupReconciler.class);

    private final BranchReconciler branchReconciler;
    private final ReviewReconciler reviewReconciler;

    public StartupReconciler(RepositoriesRepository repositoriesRepository, ProviderPlugin plugin) {
        this.branchReconciler = new BranchReconciler(repositoriesRepository, plugin);
        this.reviewReconciler = new ReviewReconciler(repositoriesRepository, plugin);
    }

    public void run() {
        LOGGER.info("Starting startup reconciliation");
        for (Reconciler r : List.of(branchReconciler, reviewReconciler)) {
            r.reconcile();
        }
        LOGGER.info("Startup reconciliation complete");
    }

    public void reconcileRepository(RepositoryRecord repo) {
        branchReconciler.reconcileOne(repo);
        reviewReconciler.reconcileOne(repo);
    }

    public void reconcileKalynxReviews(RepositoryRecord repo) {
        reviewReconciler.reconcileOne(repo);
    }
}
