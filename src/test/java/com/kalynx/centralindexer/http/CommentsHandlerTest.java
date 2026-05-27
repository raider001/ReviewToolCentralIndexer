package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.db.CommentEntry;
import com.kalynx.centralindexer.db.CommentsIndexRepository;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CommentsHandler}.
 */
class CommentsHandlerTest {

    private CommentsIndexRepository repo;
    private CommentsHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(CommentsIndexRepository.class);
        when(repo.findByReviewId(org.mockito.ArgumentMatchers.anyString())).thenReturn(List.of());
        handler = new CommentsHandler(repo);
    }

    // ── HTTP method guard ────────────────────────────────────────────────────

    @Test
    void handle_nonGetRequest_returns405() throws Exception {
        HttpExchange exchange = buildExchange("POST", "/reviews/rev-1/comments");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(405), anyLong());
    }

    // ── path routing ─────────────────────────────────────────────────────────

    @Test
    void handle_pathWithoutCommentsSegment_returns404() throws Exception {
        HttpExchange exchange = buildExchange("GET", "/reviews/rev-1/branches");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(404), anyLong());
    }

    @Test
    void handle_bareReviewsPath_returns404() throws Exception {
        HttpExchange exchange = buildExchange("GET", "/reviews");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(404), anyLong());
    }

    @Test
    void handle_emptyReviewId_returns404() throws Exception {
        HttpExchange exchange = buildExchange("GET", "/reviews//comments");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(404), anyLong());
    }

    // ── repository results ───────────────────────────────────────────────────

    @Test
    void handle_noCommentsIndexed_returns404() throws Exception {
        when(repo.findByReviewId("rev-1")).thenReturn(List.of());
        HttpExchange exchange = buildExchange("GET", "/reviews/rev-1/comments");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(404), anyLong());
    }

    @Test
    void handle_commentsFound_returns200() throws Exception {
        when(repo.findByReviewId("rev-1")).thenReturn(List.of(
                new CommentEntry("comment-uuid-001", "https://git.example.com/alice/repo.git",
                        Instant.parse("2026-05-26T10:00:00Z"))));
        HttpExchange exchange = buildExchange("GET", "/reviews/rev-1/comments");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    void handle_commentsFound_responseContainsCommentId() throws Exception {
        when(repo.findByReviewId("rev-2")).thenReturn(List.of(
                new CommentEntry("comment-uuid-abc", "https://git.example.com/alice/repo.git",
                        Instant.parse("2026-05-26T10:00:00Z"))));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("GET", "/reviews/rev-2/comments", body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("comment-uuid-abc"), "Response must contain comment_id");
    }

    @Test
    void handle_commentsFound_responseContainsRepositoryUrl() throws Exception {
        when(repo.findByReviewId("rev-3")).thenReturn(List.of(
                new CommentEntry("comment-uuid-def", "https://git.example.com/bob/project.git",
                        Instant.parse("2026-05-26T10:00:00Z"))));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("GET", "/reviews/rev-3/comments", body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("https://git.example.com/bob/project.git"),
                "Response must contain repository_url");
    }

    @Test
    void handle_commentsFound_responseContainsLastUpdated() throws Exception {
        when(repo.findByReviewId("rev-4")).thenReturn(List.of(
                new CommentEntry("comment-uuid-ghi", "https://git.example.com/alice/repo.git",
                        Instant.parse("2026-05-26T10:00:00Z"))));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("GET", "/reviews/rev-4/comments", body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("2026-05-26T10:00:00Z"), "Response must contain last_updated");
    }

    @Test
    void handle_multipleComments_allIncludedInResponse() throws Exception {
        when(repo.findByReviewId("rev-5")).thenReturn(List.of(
                new CommentEntry("comment-uuid-001", "https://git.example.com/alice/a.git",
                        Instant.parse("2026-05-26T09:00:00Z")),
                new CommentEntry("comment-uuid-002", "https://git.example.com/alice/b.git",
                        Instant.parse("2026-05-26T10:00:00Z"))));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("GET", "/reviews/rev-5/comments", body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("comment-uuid-001"), "Must contain first comment");
        assertTrue(json.contains("comment-uuid-002"), "Must contain second comment");
    }

    // ── extractReviewId static helper ────────────────────────────────────────

    @Test
    void extractReviewId_nullPath_returnsNull() {
        assertNull(CommentsHandler.extractReviewId(null));
    }

    @Test
    void extractReviewId_tooShortPath_returnsNull() {
        assertNull(CommentsHandler.extractReviewId("/reviews/rev-1"));
    }

    @Test
    void extractReviewId_tooLongPath_returnsNull() {
        assertNull(CommentsHandler.extractReviewId("/reviews/rev-1/comments/extra"));
    }

    @Test
    void extractReviewId_wrongThirdSegment_returnsNull() {
        assertNull(CommentsHandler.extractReviewId("/reviews/rev-1/branches"));
    }

    @Test
    void extractReviewId_blankId_returnsNull() {
        assertNull(CommentsHandler.extractReviewId("/reviews//comments"));
    }

    @Test
    void extractReviewId_validPath_returnsReviewId() {
        assertEquals("rev-abc-123", CommentsHandler.extractReviewId("/reviews/rev-abc-123/comments"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private HttpExchange buildExchange(String method, String path) throws Exception {
        return buildExchangeWithBody(method, path, new ByteArrayOutputStream());
    }

    private HttpExchange buildExchangeWithBody(String method, String path,
                                               ByteArrayOutputStream body) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI(path));
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(body);
        when(exchange.getRequestMethod()).thenReturn(method);
        return exchange;
    }
}
