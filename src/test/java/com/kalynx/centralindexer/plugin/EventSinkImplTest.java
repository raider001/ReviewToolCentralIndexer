package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EventSinkImpl}.
 */
class EventSinkImplTest {

    private PublisherRegistry registry;
    private BranchRepository branches;
    private ReviewsIndexRepository reviews;
    private RepositoriesRepository repos;
    private EventSinkImpl sink;

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(PublisherRegistry.class);
        branches = mock(BranchRepository.class);
        reviews  = mock(ReviewsIndexRepository.class);
        repos    = mock(RepositoriesRepository.class);
        when(repos.findByOwnerAndRepository(anyString(), anyString())).thenReturn(Optional.empty());
        when(repos.upsert(anyString(), anyString(), anyString()))
                .thenReturn(new RepositoryRecord("test-repo-id", "owner", "repo", "https://g/r", null));
        sink = new EventSinkImpl(registry, branches, reviews, repos);
    }

    // ── SSE is always published ───────────────────────────────────────────────

    @Test
    void submit_anyEvent_publishesToRegistry() {
        ReviewEvent event = reviewEvent(EventType.REVIEW_CREATED, "rev-1", Map.of());
        sink.submit(event);
        verify(registry).publish(event);
    }

    @Test
    void submit_sseOnlyConstructor_publishesToRegistry() {
        PublisherRegistry r = mock(PublisherRegistry.class);
        ReviewEvent event = reviewEvent(EventType.BRANCH_UPDATED, null,
                Map.of("branch_name", "main", "head_commit", "abc", "repository_url", "https://g/r"));
        new EventSinkImpl(r).submit(event);
        verify(r).publish(event);
    }

    // ── BRANCH_UPDATED ────────────────────────────────────────────────────────

    @Test
    void submit_branchUpdated_upsertsRepositoryAndBranch() throws Exception {
        ReviewEvent event = reviewEvent(EventType.BRANCH_UPDATED, null,
                Map.of("branch_name", "main", "head_commit", "abc123",
                       "repository_url", "https://github.com/owner/repo.git"));

        sink.submit(event);

        verify(repos).upsert("owner", "repo", "https://github.com/owner/repo.git");
        verify(branches).upsert("test-repo-id", "main", "abc123");
    }

    @Test
    void branchUpdated_noHeadCommit_doesNotUpsertBranch() throws Exception {
        ReviewEvent event = reviewEvent(EventType.BRANCH_UPDATED, null,
                Map.of("branch_name", "main", "repository_url", "https://g/r"));

        sink.submit(event);

        verify(repos).upsert(anyString(), anyString(), anyString());
        verify(branches, never()).upsert(any(), any(), any());
    }

    @Test
    void branchUpdated_noUrl_doesNotUpsertRepository() throws Exception {
        ReviewEvent event = reviewEvent(EventType.BRANCH_UPDATED, null,
                Map.of("branch_name", "main", "head_commit", "abc123"));

        sink.submit(event);

        verify(repos, never()).upsert(any(), any(), any());
        verify(branches, never()).upsert(any(), any(), any());
    }

    // ── BRANCH_DELETED ────────────────────────────────────────────────────────

    @Test
    void submit_branchDeleted_upsertsRepositoryAndDeletesBranch() throws Exception {
        ReviewEvent event = reviewEvent(EventType.BRANCH_DELETED, null,
                Map.of("branch_name", "feature-x", "repository_url", "https://g/r"));

        sink.submit(event);

        verify(repos).upsert("owner", "repo", "https://g/r");
        verify(branches).delete("test-repo-id", "feature-x");
    }

    @Test
    void branchDeleted_noBranchName_doesNotDelete() throws Exception {
        ReviewEvent event = reviewEvent(EventType.BRANCH_DELETED, null,
                Map.of("repository_url", "https://g/r"));

        sink.submit(event);

        verify(branches, never()).delete(any(), any());
    }

    // ── REVIEW events ─────────────────────────────────────────────────────────

    @Test
    void submit_reviewCreated_upsertsReviewsIndex() throws Exception {
        ReviewEvent event = reviewEvent(EventType.REVIEW_CREATED, "rev-42", Map.of());

        sink.submit(event);

        verify(reviews).upsert(eq("rev-42"), isNull(), any(), anyString());
    }

    @Test
    void submit_reviewCreated_includesUrlFromRepositoriesTable() throws Exception {
        when(repos.findByOwnerAndRepository("owner", "repo"))
                .thenReturn(Optional.of(new RepositoryRecord(
                        "repo-uuid", "owner", "repo", "https://stored-url.git", null)));
        ReviewEvent event = reviewEvent(EventType.REVIEW_CREATED, "rev-7", Map.of());

        sink.submit(event);

        verify(reviews).upsert(eq("rev-7"), isNull(), any(),
                argThat(json -> json.contains("https://stored-url.git")));
    }

    @Test
    void submit_reviewUpdated_upsertsReviewsIndex() throws Exception {
        ReviewEvent event = reviewEvent(EventType.REVIEW_UPDATED, "rev-99", Map.of());
        sink.submit(event);
        verify(reviews).upsert(eq("rev-99"), isNull(), any(), anyString());
    }

    @Test
    void submit_reviewCommentAdded_upsertsReviewsIndex() throws Exception {
        ReviewEvent event = reviewEvent(EventType.REVIEW_COMMENT_ADDED, "rev-5", Map.of());
        sink.submit(event);
        verify(reviews).upsert(eq("rev-5"), isNull(), any(), anyString());
    }

    @Test
    void reviewCreated_nullReviewId_doesNotUpsertReviewsIndex() throws Exception {
        ReviewEvent event = reviewEvent(EventType.REVIEW_CREATED, null, Map.of());
        sink.submit(event);
        verify(reviews, never()).upsert(any(), any(), any(), any());
    }

    // ── DB failure does not block SSE ─────────────────────────────────────────

    @Test
    void submit_dbFailure_doesNotPreventSsePublish() throws Exception {
        doThrow(new RuntimeException("DB down")).when(branches).upsert(any(), any(), any());
        ReviewEvent event = reviewEvent(EventType.BRANCH_UPDATED, null,
                Map.of("branch_name", "main", "head_commit", "abc",
                       "repository_url", "https://g/r"));

        sink.submit(event);   // must not throw

        verify(registry).publish(event);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ReviewEvent reviewEvent(EventType type, String reviewId, Map<String, String> payload) {
        return new ReviewEvent(0L, Instant.parse("2026-05-19T10:00:00Z"),
                "owner/repo", type, reviewId, "actor", "delivery-1", payload);
    }
}
