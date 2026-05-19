package com.kalynx.centralindexer.provider.bitbucket;

import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.spi.WebhookRouter;

import java.time.Instant;

/**
 * Built-in provider plugin for Bitbucket (Cloud and Data Center).
 *
 * <p>Registers a webhook handler on {@code POST /webhooks/bitbucket}.
 * The payload format is detected automatically: Cloud uses {@code push.changes}
 * while Data Center uses {@code refChanges}.
 *
 * <p>Required configuration keys in {@code plugin.properties}:
 * <ul>
 *   <li>{@code webhookSecret} — webhook signing secret</li>
 *   <li>{@code variant} — {@code cloud} (default) or {@code datacenter}</li>
 *   <li>{@code username} / {@code appPassword} — credentials for reconciliation API calls</li>
 *   <li>{@code baseUrl} — Data Center host URL</li>
 *   <li>{@code projectKey} — Data Center project key</li>
 * </ul>
 */
public final class BitbucketPlugin implements ProviderPlugin {

    private final BitbucketReconciler reconciler;
    private ProviderConfig config;
    private EventSink sink;

    /**
     * Creates a {@code BitbucketPlugin} with the default HTTP-based reconciler.
     */
    public BitbucketPlugin() {
        this.reconciler = new BitbucketReconciler();
    }

    @Override
    public String providerId() {
        return "bitbucket";
    }

    @Override
    public void start(ProviderConfig config, EventSink sink, WebhookRouter router) {
        this.config = config;
        this.sink = sink;
        router.registerPost("bitbucket", new BitbucketWebhookHandler(config, sink));
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

