package com.kalynx.centralindexer.plugin;

import com.kalynx.centralindexer.spi.WebhookHandler;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WebhookRouterImpl}.
 */
class WebhookRouterImplTest {

    @Test
    void dispatch_registeredSuffix_invokesHandler() {
        WebhookHandler handler = mock(WebhookHandler.class);
        WebhookRouterImpl router = new WebhookRouterImpl();
        router.registerPost("push", handler);

        Map<String, String> headers = Map.of("X-Delivery", "abc");
        byte[] body = "payload".getBytes();
        boolean dispatched = router.dispatch("push", headers, body);

        assertTrue(dispatched, "dispatch should return true for a registered suffix");
        verify(handler).handle(headers, body);
    }

    @Test
    void dispatch_unknownSuffix_returnsFalse() {
        WebhookRouterImpl router = new WebhookRouterImpl();

        boolean dispatched = router.dispatch("unknown", Map.of(), new byte[0]);

        assertFalse(dispatched, "dispatch should return false for an unregistered suffix");
    }
}

