package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.db.BranchRecord;
import com.kalynx.centralindexer.db.BranchRepository;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BranchesHandler}.
 */
class BranchesHandlerTest {

    private BranchRepository repo;
    private BranchesHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(BranchRepository.class);
        when(repo.query(any(), any(), any(), anyInt(), any())).thenReturn(List.of());
        handler = new BranchesHandler(repo);
    }

    @Test
    void handle_noParams_returns200() throws Exception {
        HttpExchange exchange = buildExchange("/branches", null);
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    void handle_noLimitParam_defaultsTo50() throws Exception {
        HttpExchange exchange = buildExchange("/branches", null);
        handler.handle(exchange);
        verify(repo).query(isNull(), isNull(), isNull(), eq(50), isNull());
    }

    @Test
    void handle_limitAtMax_returns200() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "limit=500");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    void handle_limitNotInteger_returns400() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "limit=abc");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void handle_limitZero_returns400() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "limit=0");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void handle_limitNegative_returns400() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "limit=-1");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void handle_limitAboveMax_returns400() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "limit=501");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void handle_repositoryWithNoSlash_returns400() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "repository=noslash");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void handle_repositoryWithLeadingSlash_returns400() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "repository=/repo");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void handle_repositoryWithTrailingSlash_returns400() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "repository=owner/");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void handle_invalidCursor_returns400() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "cursor=!!!notbase64");
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void handle_cursorWrongPartCount_returns400() throws Exception {
        // Valid base64 but encodes two null-separated parts, not three
        String bad = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("owner\0branchName".getBytes(StandardCharsets.UTF_8));
        HttpExchange exchange = buildExchange("/branches", "cursor=" + bad);
        handler.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(400), anyLong());
    }

    @Test
    void handle_fullPage_nextCursorPresent() throws Exception {
        List<BranchRecord> full = List.of(
                new BranchRecord("uuid-alice-repo", "alice", "repo", "feature/a"),
                new BranchRecord("uuid-alice-repo", "alice", "repo", "feature/b"));
        when(repo.query(isNull(), isNull(), isNull(), eq(2), isNull())).thenReturn(full);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/branches", "limit=2", body);
        handler.handle(exchange);

        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"next_cursor\""), "next_cursor must be present when page is full");
        assertFalse(json.contains("\"next_cursor\":null"), "next_cursor must not be null when page is full");
    }

    @Test
    void handle_partialPage_nextCursorNull() throws Exception {
        List<BranchRecord> partial = List.of(new BranchRecord("uuid-alice-repo", "alice", "repo", "main"));
        when(repo.query(isNull(), isNull(), isNull(), eq(50), isNull())).thenReturn(partial);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/branches", null, body);
        handler.handle(exchange);

        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"next_cursor\":null"), "next_cursor must be null when page is not full");
    }

    @Test
    void handle_prefixParam_passedToRepository() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "q=feature");
        handler.handle(exchange);
        verify(repo).query(eq("feature"), isNull(), isNull(), eq(50), isNull());
    }

    @Test
    void handle_repositoryParam_parsedAndPassedToRepository() throws Exception {
        HttpExchange exchange = buildExchange("/branches", "repository=alice/repo-a");
        handler.handle(exchange);
        verify(repo).query(isNull(), eq("alice"), eq("repo-a"), eq(50), isNull());
    }

    @Test
    void handle_validCursor_passedToRepository() throws Exception {
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("alice\0repo\0feature/b".getBytes(StandardCharsets.UTF_8));
        HttpExchange exchange = buildExchange("/branches", "cursor=" + cursor);
        handler.handle(exchange);
        verify(repo).query(isNull(), isNull(), isNull(), eq(50),
                eq(new String[]{"alice", "repo", "feature/b"}));
    }

    @Test
    void handle_branchesFound_responseContainsBranchNames() throws Exception {
        List<BranchRecord> records = List.of(
                new BranchRecord("uuid-alice-repo", "alice", "repo", "main"),
                new BranchRecord("uuid-alice-repo", "alice", "repo", "develop"));
        when(repo.query(any(), any(), any(), anyInt(), any())).thenReturn(records);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchangeWithBody("/branches", null, body);
        handler.handle(exchange);

        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"main\""), "Response must include branch names");
        assertTrue(json.contains("\"develop\""), "Response must include branch names");
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
