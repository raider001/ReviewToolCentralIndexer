package com.kalynx.centralindexer.provider.github;

import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLSession;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitHubBranchReconciler}.
 *
 * <p>A lightweight {@link FakeHttpClient} injects pre-programmed responses so that
 * no real network calls are made.
 */
class GitHubBranchReconcilerTest {

    private static final String REPO = "owner/repo";
    private static final ProviderConfig CONFIG =
            new ProviderConfig("github", List.of(), Map.of("apiToken", "test-token"));
    private static final ProviderConfig NO_TOKEN_CONFIG =
            new ProviderConfig("github", List.of(), Map.of());

    // -------------------------------------------------------------------------
    // fetchKalynxReviewHead
    // -------------------------------------------------------------------------

    @Test
    void fetchKalynxReviewHead_status200_returnsSha() throws Exception {
        String sha = "abc1234567890abc1234567890abc1234567890ab";
        GitHubBranchReconciler reconciler = new GitHubBranchReconciler(
                new FakeHttpClient(200, "{\"object\":{\"sha\":\"" + sha + "\"}}"), null);

        String result = reconciler.fetchKalynxReviewHead(REPO, CONFIG);

        assertEquals(sha, result);
    }

    @Test
    void fetchKalynxReviewHead_status404_returnsNull() throws Exception {
        GitHubBranchReconciler reconciler = new GitHubBranchReconciler(
                new FakeHttpClient(404, "Not Found"), null);

        assertNull(reconciler.fetchKalynxReviewHead(REPO, CONFIG));
    }

    @Test
    void fetchKalynxReviewHead_serverError_returnsNull() throws Exception {
        GitHubBranchReconciler reconciler = new GitHubBranchReconciler(
                new FakeHttpClient(503, "Service Unavailable"), null);

        assertNull(reconciler.fetchKalynxReviewHead(REPO, CONFIG));
    }

    @Test
    void fetchKalynxReviewHead_noApiToken_returnsNull() throws Exception {
        GitHubBranchReconciler reconciler = new GitHubBranchReconciler(
                new FakeHttpClient(200, "{}"), null);

        assertNull(reconciler.fetchKalynxReviewHead(REPO, NO_TOKEN_CONFIG));
    }

    // -------------------------------------------------------------------------
    // reconcileFromCommit — event mapping
    // -------------------------------------------------------------------------

    @Test
    void reconcileFromCommit_firstTitleFile_emitsReviewCreated() {
        String json = compareJson(List.of(
                "reviews/rev-001/metadata/title"));
        List<ReviewEvent> events = reconcile(json);

        assertEquals(1, events.size());
        assertEquals("rev-001", events.get(0).reviewId());
        assertEquals(EventType.REVIEW_CREATED, events.get(0).eventType());
        assertEquals(REPO, events.get(0).repository());
    }

    @Test
    void reconcileFromCommit_commentTextFile_emitsReviewCommentAdded() {
        String json = compareJson(List.of(
                "reviews/rev-002/comments/c1/text"));
        List<ReviewEvent> events = reconcile(json);

        assertEquals(1, events.size());
        assertEquals(EventType.REVIEW_COMMENT_ADDED, events.get(0).eventType());
    }

    @Test
    void reconcileFromCommit_nonTitleFile_emitsReviewUpdated() {
        String json = compareJson(List.of(
                "reviews/rev-003/metadata/description"));
        List<ReviewEvent> events = reconcile(json);

        assertEquals(1, events.size());
        assertEquals(EventType.REVIEW_UPDATED, events.get(0).eventType());
    }

    @Test
    void reconcileFromCommit_secondFileForSameReview_notFirstOccurrence() {
        // First file for rev-001 → REVIEW_CREATED (isFirst=true), second → REVIEW_UPDATED (isFirst=false)
        String json = compareJson(List.of(
                "reviews/rev-001/metadata/title",
                "reviews/rev-001/metadata/status"));
        List<ReviewEvent> events = reconcile(json);

        assertEquals(2, events.size());
        assertEquals(EventType.REVIEW_CREATED, events.get(0).eventType());
        assertEquals(EventType.REVIEW_UPDATED, events.get(1).eventType());
    }

    @Test
    void reconcileFromCommit_distinctReviews_eachTrackedAsFirstOccurrence() {
        String json = compareJson(List.of(
                "reviews/rev-001/metadata/title",
                "reviews/rev-002/metadata/title"));
        List<ReviewEvent> events = reconcile(json);

        assertEquals(2, events.size());
        assertEquals(EventType.REVIEW_CREATED, events.get(0).eventType());
        assertEquals(EventType.REVIEW_CREATED, events.get(1).eventType());
    }

    @Test
    void reconcileFromCommit_nonReviewPaths_ignored() {
        String json = compareJson(List.of(
                ".gitkeep",
                "reviews",
                "reviews/rev-001/metadata/title"));
        List<ReviewEvent> events = reconcile(json);

        assertEquals(1, events.size());
        assertEquals("rev-001", events.get(0).reviewId());
    }

    @Test
    void reconcileFromCommit_pathWithNoStreamSegment_ignored() {
        // "reviews/rev-001" has no slash after the reviewId — must be skipped
        String json = compareJson(List.of("reviews/rev-001"));
        List<ReviewEvent> events = reconcile(json);

        assertTrue(events.isEmpty());
    }

    // -------------------------------------------------------------------------
    // reconcileFromCommit — error handling
    // -------------------------------------------------------------------------

    @Test
    void reconcileFromCommit_nonSuccessResponse_emitsNoEvents() {
        List<ReviewEvent> events = new ArrayList<>();
        new GitHubBranchReconciler(new FakeHttpClient(500, "error"), null)
                .reconcileFromCommit(REPO, "from", "to", CONFIG, events::add);

        assertTrue(events.isEmpty());
    }

    @Test
    void reconcileFromCommit_noApiToken_emitsNoEvents() {
        List<ReviewEvent> events = new ArrayList<>();
        new GitHubBranchReconciler(new FakeHttpClient(200, "{}"), null)
                .reconcileFromCommit(REPO, "from", "to", NO_TOKEN_CONFIG, events::add);

        assertTrue(events.isEmpty());
    }

    @Test
    void reconcileFromCommit_emptyFilesArray_emitsNoEvents() {
        String json = """
                {"status":"ahead","ahead_by":0,"commits":[],"files":[]}
                """;
        assertTrue(reconcile(json).isEmpty());
    }

    @Test
    void reconcileFromCommit_absentFilesKey_emitsNoEvents() {
        String json = """
                {"status":"ahead","ahead_by":0,"commits":[]}
                """;
        assertTrue(reconcile(json).isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<ReviewEvent> reconcile(String compareResponseJson) {
        List<ReviewEvent> events = new ArrayList<>();
        EventSink sink = events::add;
        new GitHubBranchReconciler(new FakeHttpClient(200, compareResponseJson), null)
                .reconcileFromCommit(REPO, "fromsha", "tosha1234567890", CONFIG, sink);
        return events;
    }

    private static String compareJson(List<String> filenames) {
        String filesJson = filenames.stream()
                .map(f -> "{\"filename\":\"" + f + "\",\"status\":\"added\"}")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return """
                {
                  "status": "ahead",
                  "ahead_by": %d,
                  "commits": [
                    {"sha":"tosha1234567890","commit":{"author":{"date":"2026-05-22T10:00:00Z"},"message":"test"}}
                  ],
                  "files": [%s]
                }
                """.formatted(filenames.size(), filesJson);
    }

    // -------------------------------------------------------------------------
    // Fake HTTP infrastructure
    // -------------------------------------------------------------------------

    private static final class FakeHttpClient extends HttpClient {

        private final int statusCode;
        private final String body;

        FakeHttpClient(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (HttpResponse<T>) new FakeHttpResponse<>(statusCode, body);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushHandler) {
            return sendAsync(request, handler);
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() {
            try { return SSLContext.getDefault(); } catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    private static final class FakeHttpResponse<T> implements HttpResponse<T> {

        private final int statusCode;
        private final T body;

        FakeHttpResponse(int statusCode, T body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override public int statusCode() { return statusCode; }
        @Override public T body() { return body; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() {
            return HttpHeaders.of(Map.<String, List<String>>of(), (k, v) -> true);
        }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return null; }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
