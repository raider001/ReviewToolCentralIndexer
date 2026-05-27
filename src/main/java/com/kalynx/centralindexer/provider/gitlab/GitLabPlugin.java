package com.kalynx.centralindexer.provider.gitlab;

import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.spi.WebhookRouter;

import java.time.Instant;

/**
 * Built-in provider plugin for GitLab (cloud and self-managed).
 *
 * <p>Registers a webhook handler on {@code POST /webhooks/gitlab}.
 * Token verification uses constant-time comparison to prevent timing attacks.
 *
 * <p>Required configuration keys in {@code plugin.properties}:
 * <ul>
 *   <li>{@code webhookSecret} — the secret token configured in the GitLab webhook</li>
 *   <li>{@code apiToken} — personal access token for reconciliation API calls</li>
 *   <li>{@code baseUrl} — GitLab host (default {@code https://gitlab.com})</li>
 *   <li>{@code projectId} — numeric ID or {@code namespace/repo} path</li>
 * </ul>
 */
public final class GitLabPlugin implements ProviderPlugin {

    private final GitLabReconciler reconciler;
    private ProviderConfig config;
    private EventSink sink;

    /**
     * Creates a {@code GitLabPlugin} with the default HTTP-based reconciler.
     *
     * @param metrics the metrics collector for API call tracking; may be {@code null}
     */
    public GitLabPlugin(MetricsCollector metrics) {
        this.reconciler = new GitLabReconciler(metrics);
    }

    /** No-arg constructor for external plugin JAR loading via {@link java.util.ServiceLoader}. */
    public GitLabPlugin() {
        this(null);
    }

    @Override
    public String providerId() {
        return "gitlab";
    }

    @Override
    public void start(ProviderConfig config, EventSink sink, WebhookRouter router) {
        this.config = config;
        this.sink = sink;
        router.registerPost("gitlab", new GitLabWebhookHandler(config, sink));
    }

    @Override
    public void reconcile(String repository, Instant since) {
        if (config != null && sink != null) {
            reconciler.reconcile(repository, since, config, sink);
        }
    }

    @Override
    public void stop() {
        sink = null;
        config = null;
    }
}

