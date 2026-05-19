package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.PluginSettings;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.http.IndexerHttpServer;
import com.kalynx.centralindexer.plugin.EventSinkImpl;
import com.kalynx.centralindexer.plugin.RetryQueue;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.sse.ListenThread;
import com.kalynx.centralindexer.sse.PublisherRegistry;

import java.util.Collections;
import java.util.Map;

/**
 * Orchestrates the full application startup sequence.
 *
 * <p>The order is mandated by behaviour 7.1:
 * <ol>
 *   <li>Start the {@link RetryQueue} so in-flight events can be accepted from the
 *       first webhook request.</li>
 *   <li>Start the provider plugin via {@link ProviderPlugin#start}.</li>
 *   <li>Run {@link ReconciliationRunner} — blocks until all per-repository calls complete
 *       or time out.</li>
 *   <li>Start the {@link ListenThread} for live {@code pg_notify} delivery.</li>
 *   <li>Start the {@link PruneScheduler} for periodic event cleanup.</li>
 *   <li>Create and start the {@link IndexerHttpServer} — the TCP port is only bound here,
 *       so clients cannot connect before reconciliation completes.</li>
 * </ol>
 */
public final class Application {

    private final AppConfig config;
    private final ConnectionPool pool;
    private final EventRepository repository;
    private final ProviderPlugin plugin;
    private final WebhookRouterImpl router;
    private final PublisherRegistry registry;
    private final ListenThread listenThread;
    private final PruneScheduler pruneScheduler;
    private final RetryQueue retryQueue;

    private IndexerHttpServer server;

    /**
     * Constructs an {@code Application} with all required dependencies.
     *
     * @param config     the application configuration
     * @param pool       the database connection pool (used by the HTTP health check)
     * @param repository the event repository
     * @param plugin     the provider plugin, already loaded and validated
     * @param router     the webhook router
     * @param registry   the SSE publisher registry
     */
    public Application(AppConfig config, ConnectionPool pool, EventRepository repository,
                       ProviderPlugin plugin, WebhookRouterImpl router, PublisherRegistry registry) {
        this.config = config;
        this.pool = pool;
        this.repository = repository;
        this.plugin = plugin;
        this.router = router;
        this.registry = registry;
        this.listenThread = new ListenThread(config.getDatabase(), registry);
        this.pruneScheduler = new PruneScheduler(repository,
                config.getIndexer().getRetentionDays(),
                config.getIndexer().getPruneIntervalHours());
        this.retryQueue = new RetryQueue(config.getIndexer().getRetryQueue(), repository, registry);
    }

    /**
     * Executes the startup sequence. This method blocks until the HTTP server is
     * accepting connections.
     *
     * @throws Exception if any startup step fails
     */
    public void start() throws Exception {
        retryQueue.start();
        EventSinkImpl sink = new EventSinkImpl(repository, registry, retryQueue);
        plugin.start(buildProviderConfig(), sink, router);
        new ReconciliationRunner(config, repository, plugin).run();
        listenThread.start();
        pruneScheduler.start();
        server = new IndexerHttpServer(config, pool, router, repository, registry);
        server.start();
    }

    /**
     * Stops the HTTP server, retry queue, listen thread, and prune scheduler.
     */
    public void stop() {
        if (server != null) {
            server.stop();
        }
        retryQueue.shutdown();
        listenThread.stop();
        pruneScheduler.shutdown();
    }

    /**
     * Returns the local port the HTTP server is bound to, or {@code -1} if
     * {@link #start()} has not been called yet.
     *
     * @return the bound server port, or {@code -1}
     */
    public int getPort() {
        return server == null ? -1 : server.getPort();
    }

    private ProviderConfig buildProviderConfig() {
        PluginSettings pluginSettings = config.getPlugin();
        if (pluginSettings != null) {
            return new ProviderConfig(
                    pluginSettings.getProviderId(),
                    config.getRepositories(),
                    pluginSettings.getProperties());
        }
        return new ProviderConfig("noop", Collections.emptyList(), Map.of());
    }
}
