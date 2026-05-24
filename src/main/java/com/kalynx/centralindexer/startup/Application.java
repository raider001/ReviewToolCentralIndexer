package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.PluginSettings;
import com.kalynx.centralindexer.config.RepositoriesFileLoader;
import com.kalynx.centralindexer.config.RepositoriesFileWatcher;
import com.kalynx.centralindexer.config.RepositoryConfig;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.http.IndexerHttpServer;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.plugin.EventSinkImpl;
import com.kalynx.centralindexer.plugin.PluginLoader;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full application startup sequence.
 *
 * <p>The startup order is:
 * <ol>
 *   <li>Start the provider plugin via {@link ProviderPlugin#start}.</li>
 *   <li>Create and start the {@link IndexerHttpServer} — the TCP port is only bound here.</li>
 * </ol>
 */
public final class Application {

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    private final AppConfig config;
    private final ConnectionPool pool;
    private final ProviderPlugin plugin;
    private final WebhookRouterImpl router;
    private final PublisherRegistry registry;
    private final MetricsCollector metrics;

    private IndexerHttpServer server;

    /**
     * Constructs an {@code Application} with all required dependencies.
     *
     * @param config   the application configuration
     * @param pool     the database connection pool (used by the HTTP health check)
     * @param plugin   the provider plugin, already loaded and validated
     * @param router   the webhook router
     * @param registry the SSE publisher registry
     * @param metrics  the metrics collector
     */
    public Application(AppConfig config, ConnectionPool pool, ProviderPlugin plugin,
                       WebhookRouterImpl router, PublisherRegistry registry,
                       MetricsCollector metrics) {
        this.config = config;
        this.pool = pool;
        this.plugin = plugin;
        this.router = router;
        this.registry = registry;
        this.metrics = metrics;
    }

    /**
     * Executes the startup sequence. This method blocks until the HTTP server is
     * accepting connections.
     *
     * @throws Exception if any startup step fails
     */
    public void start() throws Exception {
        RepositoriesRepository repositoriesRepository = new RepositoriesRepository(pool);
        BranchRepository branchRepository = new BranchRepository(pool);
        ReviewsIndexRepository reviewsRepository = new ReviewsIndexRepository(pool);

        List<RepositoryConfig> repos = RepositoriesFileLoader.load();
        seedRepositories(repositoriesRepository, repos);

        EventSinkImpl sink = new EventSinkImpl(registry, branchRepository, reviewsRepository,
                repositoriesRepository);
        plugin.start(buildProviderConfig(repos), sink, router);

        StartupReconciler reconciler = new StartupReconciler(repositoriesRepository, branchRepository, plugin);
        reconciler.run();

        sink.setNewRepositoryCallback(record ->
            Thread.ofVirtual()
                .name("repo-discover-" + record.owner() + "/" + record.repository())
                .start(() -> reconciler.reconcileRepository(record)));

        java.nio.file.Path reposFilePath = RepositoriesFileLoader.resolvePath();
        RepositoriesFileWatcher watcher = new RepositoriesFileWatcher(
                reposFilePath, repos, repositoriesRepository, branchRepository, plugin);
        Thread.ofVirtual().name("repositories-file-watcher").start(watcher);
        log.info("Watching '{}' for repository additions", reposFilePath.toAbsolutePath());

        server = new IndexerHttpServer(config, pool, router, registry, branchRepository,
                reviewsRepository, repositoriesRepository, metrics);
        server.start();
    }

    /**
     * Stops the HTTP server and the provider plugin.
     */
    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    /**
     * Registers a JVM shutdown hook that stops all components in the mandated order:
     * <ol>
     *   <li>Stop accepting new HTTP requests.</li>
     *   <li>{@link PluginLoader#close()} — calls {@code plugin.stop()} then closes the
     *       {@code URLClassLoader}.</li>
     *   <li>{@link MetricsCollector#stop()} — stops the background sampler.</li>
     *   <li>{@link ConnectionPool#close()}.</li>
     * </ol>
     *
     * @param pluginLoader the active plugin loader to shut down
     * @param pool         the connection pool to close last
     * @param metrics      the metrics collector whose sampler should be stopped
     */
    public void registerShutdownHook(PluginLoader pluginLoader, ConnectionPool pool,
                                     MetricsCollector metrics) {
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            if (server != null) {
                server.stop();
            }
            try {
                pluginLoader.close();
            } catch (Exception e) {
                log.warn("Error stopping plugin during shutdown", e);
            }
            if (metrics != null) {
                metrics.stop();
            }
            pool.close();
        }));
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

    private void seedRepositories(RepositoriesRepository repo, List<RepositoryConfig> repos)
            throws SQLException, InterruptedException {
        if (repos.isEmpty()) {
            log.debug("No repositories loaded — startup DB seed skipped");
            return;
        }
        log.info("Seeding {} repository/repositories from repositories.json into DB", repos.size());
        for (RepositoryConfig r : repos) {
            repo.upsert(r.getOwner(), r.getRepository(), r.getUrl());
            log.debug("Seeded repository: {}/{} url='{}'", r.getOwner(), r.getRepository(), r.getUrl());
        }
    }

    private ProviderConfig buildProviderConfig(List<RepositoryConfig> repos) {
        PluginSettings pluginSettings = config.getPlugin();
        List<String> repoNames = repos.stream()
                .map(RepositoryConfig::ownerSlashRepo)
                .toList();
        if (pluginSettings != null) {
            return new ProviderConfig(
                    pluginSettings.getProviderId(),
                    repoNames,
                    pluginSettings.getProperties());
        }
        return new ProviderConfig("noop", Collections.emptyList(), Map.of());
    }
}