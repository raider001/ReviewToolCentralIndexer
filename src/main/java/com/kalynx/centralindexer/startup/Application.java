package com.kalynx.centralindexer.startup;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.PluginSettings;
import com.kalynx.centralindexer.config.RepositoriesFileLoader;
import com.kalynx.centralindexer.config.RepositoriesFileWatcher;
import com.kalynx.centralindexer.config.RepositoryConfig;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.CommentsIndexRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.http.IndexerHttpServer;
import com.kalynx.centralindexer.lifecycle.Lifecycle;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.plugin.EventSinkImpl;
import com.kalynx.centralindexer.plugin.PluginLoader;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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
public final class Application implements Lifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(Application.class);

    private final AppConfig config;
    private final ConnectionPool pool;
    private final ProviderPlugin plugin;
    private final WebhookRouterImpl router;
    private final PublisherRegistry registry;
    private final MetricsCollector metrics;
    private final RepositoriesRepository repositoriesRepository;
    private final BranchRepository branchRepository;
    private final ReviewsIndexRepository reviewsIndexRepository;
    private final CommentsIndexRepository commentsIndexRepository;

    private IndexerHttpServer server;

    public Application(AppConfig config,
                       ConnectionPool pool,
                       ProviderPlugin plugin,
                       WebhookRouterImpl router,
                       PublisherRegistry registry,
                       MetricsCollector metrics,
                       RepositoriesRepository repositoriesRepository,
                       BranchRepository branchRepository,
                       ReviewsIndexRepository reviewsIndexRepository,
                       CommentsIndexRepository commentsIndexRepository) {
        this.config = config;
        this.pool = pool;
        this.plugin = plugin;
        this.router = router;
        this.registry = registry;
        this.metrics = metrics;
        this.repositoriesRepository = repositoriesRepository;
        this.branchRepository = branchRepository;
        this.reviewsIndexRepository = reviewsIndexRepository;
        this.commentsIndexRepository = commentsIndexRepository;
    }

    @Override
    public void start() throws Exception {
        List<RepositoryConfig> repos = RepositoriesFileLoader.load();
        seedRepositories(repos);

        EventSinkImpl sink = new EventSinkImpl(registry, branchRepository, reviewsIndexRepository,
                repositoriesRepository);
        plugin.start(buildProviderConfig(repos), sink, router);

        StartupReconciler reconciler = new StartupReconciler(repositoriesRepository, plugin);
        CommentReconciler commentReconciler = new CommentReconciler(
                plugin, commentsIndexRepository, reviewsIndexRepository, registry);
        reconciler.run();

        sink.setNewRepositoryCallback(new NewRepositoryDiscoverer(reconciler));
        sink.setKalynxReviewsUpdateCallback(
                new KalynxReviewsUpdateHandler(reconciler, commentReconciler, repositoriesRepository));

        Path reposFilePath = RepositoriesFileLoader.resolvePath();
        RepositoriesFileWatcher watcher = new RepositoriesFileWatcher(
                reposFilePath, repos, repositoriesRepository, plugin);
        Thread.ofVirtual().name("repositories-file-watcher").start(watcher);
        LOGGER.info("Watching '{}' for repository additions", reposFilePath.toAbsolutePath());

        server = new IndexerHttpServer(config, pool, router, registry, branchRepository,
                reviewsIndexRepository, repositoriesRepository, metrics, commentsIndexRepository);
        server.start();
    }

    @Override
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
     */
    public void registerShutdownHook(PluginLoader pluginLoader, ConnectionPool pool,
                                     MetricsCollector metrics) {
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual().unstarted(() -> {
            stop();
            try {
                pluginLoader.close();
            } catch (Exception e) {
                LOGGER.warn("Error stopping plugin during shutdown", e);
            }
            if (metrics != null) {
                metrics.stop();
            }
            pool.close();
        }));
    }

    public int getPort() {
        return server == null ? -1 : server.getPort();
    }

    private void seedRepositories(List<RepositoryConfig> repos)
            throws SQLException, InterruptedException {
        if (repos.isEmpty()) {
            LOGGER.debug("No repositories loaded — startup DB seed skipped");
            return;
        }
        LOGGER.info("Seeding {} repository/repositories from repositories.json into DB", repos.size());
        for (RepositoryConfig r : repos) {
            repositoriesRepository.upsert(r.getOwner(), r.getRepository(), r.getUrl());
            LOGGER.debug("Seeded repository: {}/{} url='{}'", r.getOwner(), r.getRepository(), r.getUrl());
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
