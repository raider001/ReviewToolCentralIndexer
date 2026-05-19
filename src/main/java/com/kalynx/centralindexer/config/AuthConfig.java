package com.kalynx.centralindexer.config;

/**
 * Bearer token authentication configuration for SSE and history endpoints.
 *
 * <p>When {@link #isEnabled()} is {@code true}, all requests to {@code GET /events/stream}
 * and {@code GET /events} must carry {@code Authorization: Bearer <token>}.
 * {@code POST /webhooks/*} and {@code GET /health} are never covered by this guard.
 *
 * <p>When {@link #isEnabled()} is {@code false}, the protected endpoints are open to
 * any client that can reach the server — suitable for air-gapped or private-network
 * deployments.
 */
public final class AuthConfig {

    private boolean enabled = true;
    private String bearerToken;

    /**
     * Returns whether Bearer token authentication is active.
     *
     * @return {@code true} when auth is enforced on SSE and history endpoints
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the expected Bearer token value.
     *
     * @return the token string, or {@code null} when auth is disabled
     */
    public String getBearerToken() {
        return bearerToken;
    }
}

