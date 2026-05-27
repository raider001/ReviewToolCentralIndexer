package com.kalynx.centralindexer;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.ConfigLoader;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.CommentsIndexRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.plugin.PluginLoader;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.kalynx.centralindexer.startup.Application;
import com.kalynx.lwdi.DependencyInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Central Indexer application.
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Load configuration from {@code config.json}.</li>
 *   <li>Open the connection pool and run schema initialisation via {@link DatabaseInitializer}.</li>
 *   <li>Initialise the {@link MetricsCollector} and start its background sampler.</li>
 *   <li>Discover and validate the provider plugin via {@link PluginLoader}.</li>
 *   <li>Register all components in the {@link DependencyInjector} and inject {@link Application}.</li>
 *   <li>Delegate plugin start and HTTP server startup to {@link Application}.</li>
 * </ol>
 *
 * <p>Any failure in steps 1–6 is treated as fatal: a message is logged and the JVM exits
 * with status 1.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        try {
            AppConfig config = ConfigLoader.load();
            ConnectionPool pool = new ConnectionPool(config.getDatabase());
            new DatabaseInitializer(pool).init();

            MetricsCollector metrics = new MetricsCollector(pool);
            metrics.start();

            PluginLoader pluginLoader = new PluginLoader(config, metrics);
            ProviderPlugin plugin = pluginLoader.load();

            DependencyInjector di = new DependencyInjector();
            di.add(config);
            di.add(pool);
            di.add(metrics);
            di.add(new WebhookRouterImpl());
            di.add(new PublisherRegistry());
            di.add(ProviderPlugin.class, plugin);
            di.add(new RepositoriesRepository(pool));
            di.add(new BranchRepository(pool));
            di.add(new ReviewsIndexRepository(pool));
            di.add(new CommentsIndexRepository(pool));

            Application app = di.inject(Application.class);
            app.start();
            app.registerShutdownHook(pluginLoader, pool, metrics);
            LOGGER.info("Central Indexer started on port {}", app.getPort());

        } catch (Exception e) {
            LOGGER.error("Fatal startup error — exiting", e);
            System.exit(1);
        }
    }
}
