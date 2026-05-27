package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.db.ReviewsIndexMapper;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Core implementation of {@link EventSink}.
 *
 * <p>Each call to {@link #submit} publishes the event to connected SSE clients via
 * {@link PublisherRegistry} and persists the relevant state to the database:
 * <ul>
 *   <li>{@code BRANCH_UPDATED} — upserts the repository record (with its URL) then the branch row.</li>
 *   <li>{@code BRANCH_DELETED} — upserts the repository record then deletes the branch row.</li>
 *   <li>{@code REVIEW_CREATED}, {@code REVIEW_UPDATED}, {@code REVIEW_COMMENT_ADDED} — upserts
 *       the {@code reviews_index} row with a minimal repositories JSON. The repository URL is
 *       resolved from the {@code repositories} table if registered; otherwise left null.</li>
 * </ul>
 *
 * <p>DB failures are logged as warnings and do not propagate — SSE delivery is never
 * blocked by a persistence error.
 *
 * <p>Note: {@code review_branches} is not populated here because the review-to-branch
 * association lives in the orphan branch file content, which is not carried in webhook
 * events. It is populated by the backfill tool after reconciliation.
 */
public final class EventSinkImpl implements EventSink {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventSinkImpl.class);

    private final PublisherRegistry publisherRegistry;
    private final BranchRepository branchRepository;
    private final ReviewsIndexRepository reviewsIndexRepository;
    private final RepositoriesRepository repositoriesRepository;

    private final Set<String> seenRepositories = ConcurrentHashMap.newKeySet();
    private volatile Consumer<RepositoryRecord> newRepositoryCallback;
    private volatile BiConsumer<String, String> kalynxReviewsUpdateCallback;

    /**
     * Full constructor used in production startup.
     */
    public EventSinkImpl(PublisherRegistry publisherRegistry,
                         BranchRepository branchRepository,
                         ReviewsIndexRepository reviewsIndexRepository,
                         RepositoriesRepository repositoriesRepository) {
        this.publisherRegistry = publisherRegistry;
        this.branchRepository = branchRepository;
        this.reviewsIndexRepository = reviewsIndexRepository;
        this.repositoriesRepository = repositoriesRepository;
    }

    /**
     * SSE-only constructor used in tests and contexts where DB persistence is not required.
     */
    public EventSinkImpl(PublisherRegistry publisherRegistry) {
        this(publisherRegistry, null, null, null);
    }

    /**
     * Registers a callback invoked the first time a repository is seen via a push webhook.
     * The callback receives a {@link RepositoryRecord} with a {@code null} cursor so the
     * reconciler treats it as a first-run and indexes the full review tree.
     */
    public void setNewRepositoryCallback(Consumer<RepositoryRecord> callback) {
        this.newRepositoryCallback = callback;
    }

    /**
     * Registers a callback invoked whenever a {@code kalynx-reviews} branch push is persisted.
     * The callback receives the repository {@code owner} and {@code repository} as separate strings.
     * Used to trigger live review reconciliation without blocking the webhook response.
     */
    public void setKalynxReviewsUpdateCallback(BiConsumer<String, String> callback) {
        this.kalynxReviewsUpdateCallback = callback;
    }

    @Override
    public void submit(ReviewEvent event) {
        LOGGER.info("Event received: type='{}' repo='{}' reviewId='{}'",
                event.eventType(), event.repository(), event.reviewId());
        publisherRegistry.publish(enrichWithRepoUrl(event));
        try {
            persist(event);
        } catch (Exception e) {
            LOGGER.warn("Failed to persist {} event for '{}' to DB: {}",
                    event.eventType(), event.repository(), e.getMessage());
        }
    }

    private ReviewEvent enrichWithRepoUrl(ReviewEvent event) {
        return switch (event.eventType()) {
            case REVIEW_CREATED, REVIEW_UPDATED, REVIEW_CLOSED,
                 REVIEW_COMMENT_ADDED, REVIEW_COMMENT_UPDATED -> {
                if (event.payload().containsKey("repository_url")) yield event;
                String[] parts = splitRepo(event.repository());
                String url = resolveRepoUrl(parts[0], parts[1]);
                if (url == null) yield event;
                Map<String, String> enriched = new HashMap<>(event.payload());
                enriched.put("repository_url", url);
                yield new ReviewEvent(event.sequenceNo(), event.timestamp(), event.repository(),
                        event.eventType(), event.reviewId(), event.actorUser(), event.deliveryId(),
                        enriched);
            }
            default -> event;
        };
    }

    // -------------------------------------------------------------------------

    private void persist(ReviewEvent event) throws SQLException, InterruptedException {
        switch (event.eventType()) {
            case BRANCH_UPDATED        -> persistBranchUpdated(event);
            case BRANCH_DELETED        -> persistBranchDeleted(event);
            case REVIEW_CREATED,
                 REVIEW_UPDATED,
                 REVIEW_COMMENT_ADDED  -> persistReviewEvent(event);
            default                    -> {}
        }
    }

    private void persistBranchUpdated(ReviewEvent event) throws SQLException, InterruptedException {
        if (branchRepository == null) {
            LOGGER.warn("DB persistence skipped for BRANCH_UPDATED '{}': branchRepository not wired",
                    event.repository());
            return;
        }
        String[] parts = splitRepo(event.repository());
        String owner = parts[0], repo = parts[1];
        String branchName  = event.payload().get("branch_name");
        String headCommit  = event.payload().get("head_commit");
        String url         = event.payload().get("repository_url");

        if (url == null) {
            LOGGER.warn("BRANCH_UPDATED for '{}' has no repository_url in payload — skipping DB write " +
                     "(payload keys: {})", event.repository(), event.payload().keySet());
            return;
        }

        RepositoryRecord repoRecord = repositoriesRepository.upsert(owner, repo, url);

        if (seenRepositories.add(owner + "/" + repo)) {
            Consumer<RepositoryRecord> cb = newRepositoryCallback;
            if (cb != null) {
                LOGGER.info("First push seen for '{}' — triggering dynamic onboarding", owner + "/" + repo);
                cb.accept(repoRecord);
            }
        }
        LOGGER.debug("Repository upserted: {}/{} url='{}'", owner, repo, url);

        if (branchName != null && headCommit != null) {
            branchRepository.upsert(repoRecord.repositoryId(), branchName, headCommit);
            LOGGER.debug("Branch upserted: {}/{} branch='{}' head='{}'",
                    owner, repo, branchName, headCommit);
        } else {
            LOGGER.warn("BRANCH_UPDATED for '{}' missing branch_name='{}' or head_commit='{}' — " +
                     "repository upserted but branch row skipped",
                    event.repository(), branchName, headCommit);
        }

        if ("kalynx-reviews".equals(branchName) && headCommit != null) {
            BiConsumer<String, String> cb = kalynxReviewsUpdateCallback;
            if (cb != null) {
                LOGGER.info("kalynx-reviews push detected for '{}/{}' — triggering live review reconciliation",
                        owner, repo);
                cb.accept(owner, repo);
            }
        }
    }

    private void persistBranchDeleted(ReviewEvent event) throws SQLException, InterruptedException {
        if (branchRepository == null) {
            LOGGER.warn("DB persistence skipped for BRANCH_DELETED '{}': branchRepository not wired",
                    event.repository());
            return;
        }
        String[] parts = splitRepo(event.repository());
        String owner = parts[0], repo = parts[1];
        String branchName = event.payload().get("branch_name");
        String url        = event.payload().get("repository_url");

        String repositoryId = ensureRepository(owner, repo, url);

        if (branchName != null && repositoryId != null) {
            branchRepository.delete(repositoryId, branchName);
            LOGGER.debug("Branch deleted: {}/{} branch='{}'", owner, repo, branchName);
        } else {
            LOGGER.warn("BRANCH_DELETED for '{}' has no branch_name in payload — nothing deleted",
                    event.repository());
        }
    }

    private void persistReviewEvent(ReviewEvent event) throws SQLException, InterruptedException {
        if (reviewsIndexRepository == null) {
            LOGGER.warn("DB persistence skipped for {} '{}': reviewsIndexRepository not wired",
                    event.eventType(), event.reviewId());
            return;
        }
        if (event.reviewId() == null) {
            LOGGER.warn("Skipping reviews_index upsert for {} event — reviewId is null",
                    event.eventType());
            return;
        }
        String[] parts = splitRepo(event.repository());
        String owner = parts[0], repo = parts[1];
        String url = resolveRepoUrl(owner, repo);
        if (url == null) {
            LOGGER.warn("No URL found in repositories table for {}/{} — review '{}' will be stored " +
                     "without repository_url; register the repo via POST /repositories to fix this",
                    owner, repo, event.reviewId());
        }

        String status     = event.payload().get("status");
        String branchName = event.payload().get("branchName");
        List<ReviewsIndexMapper.RepoEntry> entries = List.of(
                new ReviewsIndexMapper.RepoEntry(owner, repo, url, branchName, null));
        reviewsIndexRepository.upsert(
                event.reviewId(), status, event.timestamp(),
                ReviewsIndexMapper.toRepositoriesJson(entries));
        LOGGER.debug("Review upserted in reviews_index: id='{}' repo='{}/{}' url='{}'",
                event.reviewId(), owner, repo, url);
    }

    // -------------------------------------------------------------------------

    private String ensureRepository(String owner, String repo, String url)
            throws SQLException, InterruptedException {
        if (repositoriesRepository == null || url == null) return null;
        RepositoryRecord record = repositoriesRepository.upsert(owner, repo, url);
        LOGGER.debug("Repository upserted: {}/{} url='{}'", owner, repo, url);
        return record.repositoryId();
    }

    private String resolveRepoUrl(String owner, String repo) {
        if (repositoriesRepository == null) return null;
        try {
            return repositoriesRepository.findByOwnerAndRepository(owner, repo)
                    .map(r -> r.url())
                    .orElse(null);
        } catch (Exception e) {
            LOGGER.warn("Could not resolve URL for {}/{}: {}", owner, repo, e.getMessage());
            return null;
        }
    }

    private static String[] splitRepo(String fullName) {
        int slash = fullName == null ? -1 : fullName.indexOf('/');
        if (slash < 0) return new String[]{fullName == null ? "" : fullName, ""};
        return new String[]{fullName.substring(0, slash), fullName.substring(slash + 1)};
    }
}
