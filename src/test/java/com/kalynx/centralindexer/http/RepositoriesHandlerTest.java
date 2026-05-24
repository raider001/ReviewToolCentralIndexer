package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.RepositoryRecord;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RepositoriesHandler}.
 */
class RepositoriesHandlerTest {

    private RepositoriesRepository repo;
    private RepositoriesHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(RepositoriesRepository.class);
        when(repo.findAll()).thenReturn(List.of());
        handler = new RepositoriesHandler(repo);
    }

    // ── GET /repositories ─────────────────────────────────────────────────────

    @Test
    void get_emptyRepository_returns200WithEmptyItems() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchange("GET", "/repositories", null, body);

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"items\":[]"));
    }

    @Test
    void get_withRepositories_returnsItemsJson() throws Exception {
        when(repo.findAll()).thenReturn(List.of(
                new RepositoryRecord("my-org", "my-repo", "https://git.example.com/my-repo.git", null)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpExchange exchange = buildExchange("GET", "/repositories", null, body);

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(200), anyLong());
        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"my-org\""));
        assertTrue(json.contains("\"my-repo\""));
        assertTrue(json.contains("\"https://git.example.com/my-repo.git\""));
    }

    @Test
    void get_methodNotAllowed_returns405() throws Exception {
        HttpExchange exchange = buildExchange("DELETE", "/repositories", null, new ByteArrayOutputStream());

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(405), anyLong());
    }

    // ── POST /repositories ────────────────────────────────────────────────────

    @Test
    void post_validBody_insertsAndReturns201() throws Exception {
        when(repo.findAll()).thenReturn(List.of());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String requestBody = """
            {"owner":"my-org","repository":"my-repo","url":"https://git/repo.git"}
            """;
        HttpExchange exchange = buildExchangeWithBody("POST", "/repositories", requestBody, body);

        handler.handle(exchange);

        verify(repo).upsert(eq("my-org"), eq("my-repo"), eq("https://git/repo.git"));
        verify(exchange).sendResponseHeaders(eq(201), anyLong());
    }

    @Test
    void post_existingRepository_updatesAndReturns200() throws Exception {
        when(repo.findAll()).thenReturn(List.of(
                new RepositoryRecord("my-org", "my-repo", "https://old-url.git", null)));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String requestBody = """
            {"owner":"my-org","repository":"my-repo","url":"https://new-url.git"}
            """;
        HttpExchange exchange = buildExchangeWithBody("POST", "/repositories", requestBody, body);

        handler.handle(exchange);

        verify(repo).upsert(eq("my-org"), eq("my-repo"), eq("https://new-url.git"));
        verify(exchange).sendResponseHeaders(eq(200), anyLong());
    }

    @Test
    void post_missingOwner_returns400() throws Exception {
        HttpExchange exchange = buildExchangeWithBody("POST", "/repositories",
                "{\"repository\":\"my-repo\",\"url\":\"https://git/repo.git\"}",
                new ByteArrayOutputStream());

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        verify(repo, never()).upsert(any(), any(), any());
    }

    @Test
    void post_missingRepository_returns400() throws Exception {
        HttpExchange exchange = buildExchangeWithBody("POST", "/repositories",
                "{\"owner\":\"my-org\",\"url\":\"https://git/repo.git\"}",
                new ByteArrayOutputStream());

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        verify(repo, never()).upsert(any(), any(), any());
    }

    @Test
    void post_missingUrl_returns400() throws Exception {
        HttpExchange exchange = buildExchangeWithBody("POST", "/repositories",
                "{\"owner\":\"my-org\",\"repository\":\"my-repo\"}",
                new ByteArrayOutputStream());

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        verify(repo, never()).upsert(any(), any(), any());
    }

    @Test
    void post_invalidJson_returns400() throws Exception {
        HttpExchange exchange = buildExchangeWithBody("POST", "/repositories",
                "not valid json",
                new ByteArrayOutputStream());

        handler.handle(exchange);

        verify(exchange).sendResponseHeaders(eq(400), anyLong());
        verify(repo, never()).upsert(any(), any(), any());
    }

    @Test
    void post_responseContainsRegisteredFields() throws Exception {
        when(repo.findAll()).thenReturn(List.of());
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String requestBody = "{\"owner\":\"org\",\"repository\":\"repo\",\"url\":\"https://git/r.git\"}";
        HttpExchange exchange = buildExchangeWithBody("POST", "/repositories", requestBody, body);

        handler.handle(exchange);

        String json = body.toString(StandardCharsets.UTF_8);
        assertTrue(json.contains("\"org\""));
        assertTrue(json.contains("\"repo\""));
        assertTrue(json.contains("\"https://git/r.git\""));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpExchange buildExchange(String method, String path, String query,
                                       ByteArrayOutputStream responseBody) throws Exception {
        return buildExchangeWithBody(method, path, "", responseBody);
    }

    private HttpExchange buildExchangeWithBody(String method, String path,
                                               String requestBody,
                                               ByteArrayOutputStream responseBody) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI(path));
        when(exchange.getRequestMethod()).thenReturn(method);
        when(exchange.getResponseHeaders()).thenReturn(new Headers());
        byte[] bodyBytes = requestBody == null ? new byte[0]
                : requestBody.getBytes(StandardCharsets.UTF_8);
        when(exchange.getRequestBody()).thenReturn(new ByteArrayInputStream(bodyBytes));
        when(exchange.getResponseBody()).thenReturn(responseBody);
        return exchange;
    }
}
