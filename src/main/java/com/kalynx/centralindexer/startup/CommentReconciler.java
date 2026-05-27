package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.comments.CommentChange;
import com.kalynx.centralindexer.comments.CommentChangeDetector;
import com.kalynx.centralindexer.comments.CommentEventType;
import com.kalynx.centralindexer.db.CommentsIndexRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects comment changes on a {@code kalynx-reviews} push and persists / publishes them.
 *
 * <p>Called from the live {@code kalynxReviewsUpdateCallback} after review reconciliation.
 * For each changed {@code comment_id} in the pushed commit range:
 * <ol>
 *   <li>Checks the review is not CLOSED — closed reviews are frozen.</li>
 *   <li>Upserts {@code comments_index}.</li>
 *   <li>Publishes an SSE event via {@link PublisherRegistry}.</li>
 * </ol>
 */
public final class CommentReconciler implements Reconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(CommentReconciler.class);

    private final ProviderPlugin plugin;
    private final CommentsIndexRepository commentsIndexRepository;
    private final ReviewsIndexRepository reviewsIndexRepository;
    private final PublisherRegistry publisherRegistry;

    /**
     * Constructs a {@code CommentReconciler}.
     *
     * @param plugin                  the provider plugin; used to list changed paths
     * @param commentsIndexRepository repository for {@code comments_index}
     * @param reviewsIndexRepository  repository for reading review status
     * @param publisherRegistry       SSE publisher
     */
    public CommentReconciler(ProviderPlugin plugin,
                             CommentsIndexRepository commentsIndexRepository,
                             ReviewsIndexRepository reviewsIndexRepository,
                             PublisherRegistry publisherRegistry) {
        this.plugin = plugin;
        this.commentsIndexRepository = commentsIndexRepository;
        this.reviewsIndexRepository = reviewsIndexRepository;
        this.publisherRegistry = publisherRegistry;
    }

    @Override
    public void reconcile() {
        // Comment reconciliation is driven by live webhook pushes via processLivePush.
    }

    /**
     * Detects and processes comment changes for a live {@code kalynx-reviews} push.
     *
     * <p>The old commit is taken from {@code repoRecord.kalynxReviewHead()} (the cursor stored
     * before this push). The new commit is fetched live from the plugin. If the old cursor
     * is {@code null} (first run), no comment detection is attempted.
     *
     * @param repoRecord the repository record with the pre-push cursor
     */
    public void processLivePush(RepositoryRecord repoRecord) {
        String repoPath = repoRecord.owner() + "/" + repoRecord.repository();
        String fromCommit = repoRecord.kalynxReviewHead();
        if (fromCommit == null) {
            LOGGER.debug("{}: no prior cursor — skipping comment detection", repoPath);
            return;
        }
        try {
            String toCommit = plugin.fetchKalynxReviewHead(repoPath);
            if (toCommit == null || toCommit.equals(fromCommit)) {
                LOGGER.debug("{}: no new commit detected — skipping comment detection", repoPath);
                return;
            }
            processRange(repoRecord, fromCommit, toCommit);
        } catch (Exception e) {
            LOGGER.warn("{}: comment detection failed: {}", repoPath, e.getMessage());
        }
    }

    /**
     * Detects and processes comment changes in an explicit commit range.
     * Exposed for startup reconciliation and testing.
     *
     * @param repoRecord the repository record (supplies {@code repositoryId} and URL)
     * @param fromCommit the exclusive lower-bound commit SHA
     * @param toCommit   the inclusive upper-bound commit SHA
     */
    public void processRange(RepositoryRecord repoRecord, String fromCommit, String toCommit) {
        String repoPath = repoRecord.owner() + "/" + repoRecord.repository();
        try {
            List<String> changedPaths = plugin.listChangedPaths(repoPath, fromCommit, toCommit);
            List<CommentChange> changes = CommentChangeDetector.detect(changedPaths);
            if (changes.isEmpty()) return;

            LOGGER.info("{}: {} comment change(s) in range {}..{}",
                    repoPath, changes.size(), abbrev(fromCommit), abbrev(toCommit));
            for (CommentChange change : changes) {
                processChange(repoRecord, change);
            }
        } catch (Exception e) {
            LOGGER.warn("{}: comment detection failed for range {}..{}: {}",
                    repoPath, abbrev(fromCommit), abbrev(toCommit), e.getMessage());
        }
    }

    private void processChange(RepositoryRecord repoRecord, CommentChange change) throws Exception {
        String status = reviewsIndexRepository.findStatusById(change.reviewId()).orElse(null);
        if ("CLOSED".equalsIgnoreCase(status)) {
            LOGGER.debug("Skipping comment for closed review '{}'", change.reviewId());
            return;
        }

        Instant now = Instant.now();
        commentsIndexRepository.upsert(change.commentId(), change.reviewId(),
                repoRecord.repositoryId(), now);

        EventType eventType = change.eventType() == CommentEventType.ADDED
                ? EventType.REVIEW_COMMENT_ADDED
                : EventType.REVIEW_COMMENT_UPDATED;

        Map<String, String> payload = new HashMap<>();
        payload.put("repository_url", repoRecord.url());
        payload.put("comment_id", change.commentId());
        ReviewEvent event = new ReviewEvent(0L, now,
                repoRecord.owner() + "/" + repoRecord.repository(),
                eventType, change.reviewId(), null, null, payload);
        publisherRegistry.publish(event);

        LOGGER.debug("Comment processed: review='{}' comment='{}' type='{}'",
                change.reviewId(), change.commentId(), eventType);
    }

    private static String abbrev(String sha) {
        return sha != null && sha.length() >= 7 ? sha.substring(0, 7) : sha;
    }
}
