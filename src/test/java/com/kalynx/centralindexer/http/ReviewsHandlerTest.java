package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.db.ReviewRecord;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReviewsHandler}.
 */
class ReviewsHandlerTest {

    private ReviewsIndexRepository repo;
    private ReviewsHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(ReviewsIndexRepository.class);
        when(repo.query(any(), any())).thenReturn(List.of());
        handler = new ReviewsHandler(repo);
    }

    @Test
    void noParamsReturns200() throws Exception {
        HttpExchange exchange = buildExchange("/reviews", null);
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    void noParamsPassesNullFiltersToRepository() throws Exception {
        HttpExchange exchange = buildExchange("/reviews", null);
        handler.handle(exchange);
        verify(repo).query(isNull(), isNull());
    }

    @Test
    void invalidSinceReturns400() throws Exception {
        HttpExchange exchange = buildExchange("/reviews", "since=not-a-date");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void validSinceParsedAndPassedToRepository() throws Exception {
        Instant expected = Instant.parse("2026-01-01T00:00:00Z");
        HttpExchange exchange = buildExchange("/reviews", "since=2026-01-01T00%3A00%3A00Z");
        // Use unencoded form for simplicity — parseQuery splits on = only
        exchange = buildExchange("/reviews", "since=2026-01-01T00:00:00Z");
        handler.handle(exchange);
        verify(repo).query(eq(expected), isNull());
    }

    @Test
    void statusParamParsedAndPassedToRepository() throws Exception {
        HttpExchange exchange = buildExchange("/reviews", "status=OPEN");
        handler.handle(exchange);
        verify(repo).query(isNull(), eq(List.of("OPEN")));
    }

    @Test
    void multipleStatusValuesParsedFromComma() throws Exception {
        HttpExchange exchange = buildExchange("/reviews", "status=OPEN,APPROVED");
        handler.handle(exchange);
        verify(repo).query(isNull(), eq(List.of("OPEN", "APPROVED")));
    }

    @Test
    void emptyResultsHasEmptyItemsArray() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/reviews", null, body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"items\":[]"), "Empty result must produce items:[]");
    }

    @Test
    void responseContainsReviewId() throws Exception {
        when(repo.query(any(), any())).thenReturn(List.of(
                new ReviewRecord("rev-123", "OPEN", Instant.parse("2026-01-01T00:00:00Z"), null)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/reviews", null, body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"rev-123\""), "Response must contain review_id");
    }

    @Test
    void responseContainsStatus() throws Exception {
        when(repo.query(any(), any())).thenReturn(List.of(
                new ReviewRecord("rev-1", "APPROVED", Instant.now(), null)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/reviews", null, body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"APPROVED\""), "Response must contain status");
    }

    @Test
    void reviewBranchExtractedFromFirstRepoEntry() throws Exception {
        String reposJson = "[{\"owner\":\"alice\",\"repository\":\"repo\","
                + "\"repositoryUrl\":\"https://example.com\","
                + "\"branchName\":\"feature/x\",\"headCommit\":\"abc\"}]";
        when(repo.query(any(), any())).thenReturn(List.of(
                new ReviewRecord("rev-1", "OPEN", Instant.now(), reposJson)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/reviews", null, body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"feature/x\""), "review_branch must be extracted from first repo entry");
    }

    @Test
    void baseBranchIsNull() throws Exception {
        when(repo.query(any(), any())).thenReturn(List.of(
                new ReviewRecord("rev-1", "OPEN", Instant.now(), null)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/reviews", null, body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"base_branch\":null"), "base_branch must be null");
    }

    @Test
    void repositoriesDeduplicatedByOwnerRepo() throws Exception {
        String reposJson = "[{\"owner\":\"alice\",\"repository\":\"repo\","
                + "\"repositoryUrl\":\"https://example.com\","
                + "\"branchName\":\"feat-a\",\"headCommit\":\"aaa\"},"
                + "{\"owner\":\"alice\",\"repository\":\"repo\","
                + "\"repositoryUrl\":\"https://example.com\","
                + "\"branchName\":\"feat-b\",\"headCommit\":\"bbb\"}]";
        when(repo.query(any(), any())).thenReturn(List.of(
                new ReviewRecord("rev-1", "OPEN", Instant.now(), reposJson)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/reviews", null, body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        // alice/repo should appear only once in the repositories array
        int first = json.indexOf("alice/repo");
        int last = json.lastIndexOf("alice/repo");
        assertTrue(first == last, "alice/repo must appear exactly once in repositories");
    }

    @Test
    void repositoriesContainsRepositoryUrl() throws Exception {
        String reposJson = "[{\"owner\":\"alice\",\"repository\":\"repo\","
                + "\"repositoryUrl\":\"https://git.example.com/alice/repo\","
                + "\"branchName\":\"main\",\"headCommit\":\"abc\"}]";
        when(repo.query(any(), any())).thenReturn(List.of(
                new ReviewRecord("rev-1", "OPEN", Instant.now(), reposJson)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/reviews", null, body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("https://git.example.com/alice/repo"), "repository_url must be in response");
    }

    @Test
    void nullRepositoriesJsonProducesEmptyRepositoriesList() throws Exception {
        when(repo.query(any(), any())).thenReturn(List.of(
                new ReviewRecord("rev-1", "OPEN", Instant.now(), null)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/reviews", null, body);
        handler.handle(exchange);
        String json = body.toString(StandardCharsets.UTF_8);
        assertFalse(json.isEmpty());
        assertTrue(json.contains("\"repositories\":[]"), "Null repositoriesJson must produce empty list");
    }

    private HttpExchange buildExchange(String path, String query) throws Exception {
        return buildExchangeWithBody(path, query, new ByteArrayOutputStream());
    }

    private HttpExchange buildExchangeWithBody(String path, String query,
                                               ByteArrayOutputStream body) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        URI uri = query != null ? new URI(path + "?" + query) : new URI(path);
        when(exchange.getRequestURI()).thenReturn(uri);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        when(exchange.getResponseBody()).thenReturn(body);
        when(exchange.getRequestMethod()).thenReturn("GET");
        return exchange;
    }
}
