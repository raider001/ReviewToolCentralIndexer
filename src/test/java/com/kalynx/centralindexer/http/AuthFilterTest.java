package com.kalynx.centralindexer.http;
import com.kalynx.centralindexer.config.AuthConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * Unit tests for {@link AuthFilter}.
 */
class AuthFilterTest {
    @Test
    void handle_missingAuthHeader_returns401() throws Exception {
        AuthFilter filter = new AuthFilter(authConfig(true, "secret"), mock(HttpHandler.class));
        HttpExchange exchange = exchangeFor("/events", null);
        filter.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(401), anyLong());
    }
    @Test
    void handle_wrongToken_returns403() throws Exception {
        HttpHandler downstream = mock(HttpHandler.class);
        AuthFilter filter = new AuthFilter(authConfig(true, "secret"), downstream);
        HttpExchange exchange = exchangeFor("/events", "Bearer wrong");
        filter.handle(exchange);
        verify(exchange).sendResponseHeaders(eq(403), anyLong());
        verify(downstream, never()).handle(any());
    }
    @Test
    void handle_correctToken_delegatesToDownstream() throws Exception {
        HttpHandler downstream = mock(HttpHandler.class);
        AuthFilter filter = new AuthFilter(authConfig(true, "secret"), downstream);
        HttpExchange exchange = exchangeFor("/events", "Bearer secret");
        filter.handle(exchange);
        verify(downstream).handle(exchange);
        verify(exchange, never()).sendResponseHeaders(eq(401), anyLong());
        verify(exchange, never()).sendResponseHeaders(eq(403), anyLong());
    }
    @Test
    void handle_authDisabled_delegatesToDownstream() throws Exception {
        HttpHandler downstream = mock(HttpHandler.class);
        AuthFilter filter = new AuthFilter(authConfig(false, null), downstream);
        HttpExchange exchange = exchangeFor("/events", null);
        filter.handle(exchange);
        verify(downstream).handle(exchange);
        verify(exchange, never()).sendResponseHeaders(eq(401), anyLong());
        verify(exchange, never()).sendResponseHeaders(eq(403), anyLong());
    }
    @Test
    void handle_healthPath_bypassesAuthAndDelegates() throws Exception {
        HttpHandler downstream = mock(HttpHandler.class);
        AuthFilter filter = new AuthFilter(authConfig(true, "secret"), downstream);
        HttpExchange exchange = exchangeFor("/health", null);
        filter.handle(exchange);
        verify(downstream).handle(exchange);
        verify(exchange, never()).sendResponseHeaders(eq(401), anyLong());
    }
    @Test
    void handle_webhooksPath_bypassesAuthAndDelegates() throws Exception {
        HttpHandler downstream = mock(HttpHandler.class);
        AuthFilter filter = new AuthFilter(authConfig(true, "secret"), downstream);
        HttpExchange exchange = exchangeFor("/webhooks/push", null);
        filter.handle(exchange);
        verify(downstream).handle(exchange);
        verify(exchange, never()).sendResponseHeaders(eq(401), anyLong());
    }
    private AuthConfig authConfig(boolean enabled, String token) {
        AuthConfig config = mock(AuthConfig.class);
        when(config.isEnabled()).thenReturn(enabled);
        when(config.getBearerToken()).thenReturn(token);
        return config;
    }
    private HttpExchange exchangeFor(String path, String authHeaderValue) throws Exception {
        HttpExchange exchange = mock(HttpExchange.class);
        when(exchange.getRequestURI()).thenReturn(new URI(path));
        Headers headers = new Headers();
        if (authHeaderValue != null) {
            headers.add("Authorization", authHeaderValue);
        }
        when(exchange.getRequestHeaders()).thenReturn(headers);
        OutputStream os = new ByteArrayOutputStream();
        when(exchange.getResponseBody()).thenReturn(os);
        return exchange;
    }
}