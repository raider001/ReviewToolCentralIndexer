package com.kalynx.centralindexer;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.ConfigLoader;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.plugin.PluginLoader;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.kalynx.centralindexer.startup.Application;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the Central Indexer application.
 *
 * <p>Startup sequence (behaviour 7.1):
 * <ol>
 *   <li>Load configuration from {@code config.json} (or the path resolved by
 *       {@link ConfigLoader}).</li>
 *   <li>Open the connection pool and run {@code CREATE TABLE IF NOT EXISTS} migrations via
 *       {@link DatabaseInitializer}.</li>
 *   <li>Discover and validate the provider plugin JAR via {@link PluginLoader}.</li>
 *   <li>Delegate the remaining startup steps — plugin start, reconciliation, LISTEN thread,
 *       prune scheduler, and HTTP server — to {@link Application}.</li>
 * </ol>
 *
 * <p>Any failure in steps 1–4 is treated as fatal: a message is logged and the JVM exits
 * with status 1.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {
    }

    /**
     * Application entry point.
     *
     * @param args command-line arguments (not used)
     */
    public static void main(String[] args) {
        try {
            AppConfig config = ConfigLoader.load();
            ConnectionPool pool = new ConnectionPool(config.getDatabase());
            new DatabaseInitializer(pool).init();

            EventRepository repository = new EventRepository(pool);
            WebhookRouterImpl router = new WebhookRouterImpl();
            PublisherRegistry registry = new PublisherRegistry();

            PluginLoader pluginLoader = new PluginLoader(config);
            ProviderPlugin plugin = pluginLoader.load();

            Application app = new Application(config, pool, repository, plugin, router, registry);
            app.start();
            app.registerShutdownHook(pluginLoader, pool);
            log.info("Central Indexer started on port {}", app.getPort());
        } catch (Exception e) {
            log.error("Fatal startup error — exiting", e);
            System.exit(1);
        }
    }
}


