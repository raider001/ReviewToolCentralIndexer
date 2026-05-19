package com.kalynx.centralindexer.provider.github;

import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.spi.WebhookRouter;

import java.time.Instant;

/**
 * Built-in provider plugin for GitHub.
 *
 * <p>Registers a webhook handler on {@code POST /webhooks/github} that verifies
 * HMAC-SHA256 signatures, parses push payloads, and submits {@code ReviewEvent}s
 * for any {@code refs/notes/reviews/*} or {@code refs/heads/*} push.
 *
 * <p>Required configuration keys in {@code plugin.properties}:
 * <ul>
 *   <li>{@code webhookSecret} — global secret for all repos (or per-repo via
 *       {@code {owner/repo}.webhookSecret})</li>
 *   <li>{@code apiToken} — GitHub personal access token used during reconciliation</li>
 * </ul>
 */
public final class GitHubPlugin implements ProviderPlugin {

    private final GitHubReconciler reconciler;
    private ProviderConfig config;
    private EventSink sink;

    /**
     * Creates a {@code GitHubPlugin} with the default HTTP-based reconciler.
     */
    public GitHubPlugin() {
        this.reconciler = new GitHubReconciler();
    }

    @Override
    public String providerId() {
        return "github";
    }

    @Override
    public void start(ProviderConfig config, EventSink sink, WebhookRouter router) {
        this.config = config;
        this.sink = sink;
        router.registerPost("github", new GitHubWebhookHandler(config, sink));
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
