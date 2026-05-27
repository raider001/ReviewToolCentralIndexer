package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.db.RepositoriesRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BiConsumer;

final class KalynxReviewsUpdateHandler implements BiConsumer<String, String> {

    private static final Logger LOGGER = LoggerFactory.getLogger(KalynxReviewsUpdateHandler.class);

    private final StartupReconciler reconciler;
    private final CommentReconciler commentReconciler;
    private final RepositoriesRepository repositoriesRepository;

    KalynxReviewsUpdateHandler(StartupReconciler reconciler,
                                CommentReconciler commentReconciler,
                                RepositoriesRepository repositoriesRepository) {
        this.reconciler = reconciler;
        this.commentReconciler = commentReconciler;
        this.repositoriesRepository = repositoriesRepository;
    }

    @Override
    public void accept(String owner, String repo) {
        Thread.ofVirtual()
                .name("kalynx-reviews-live-" + owner + "/" + repo)
                .start(() -> {
                    try {
                        repositoriesRepository.findByOwnerAndRepository(owner, repo)
                                .ifPresent(record -> {
                                    reconciler.reconcileKalynxReviews(record);
                                    commentReconciler.processLivePush(record);
                                });
                    } catch (Exception e) {
                        LOGGER.warn("kalynx-reviews live reconciliation failed for {}/{}: {}",
                                owner, repo, e.getMessage());
                    }
                });
    }
}
