package com.kalynx.centralindexer;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.ConfigLoader;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.DatabaseInitializer;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.gui.IndexerGui;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.plugin.PluginLoader;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.spi.ProviderPlugin;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.kalynx.centralindexer.startup.Application;
import com.kalynx.swingtheme.theme.ThemeManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.Arrays;

/**
 * Entry point for the Central Indexer application.
 *
 * <p>Pass {@code --gui} to launch the optional Swing management window.
 * Without this flag the process runs fully headless (the default for Docker deployments).
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Load configuration from {@code config.json}.</li>
 *   <li>Initialise the shared {@link MetricsCollector} and start its background sampler.</li>
 *   <li>Open the connection pool and run schema initialisation via {@link DatabaseInitializer}.</li>
 *   <li>Discover and validate the provider plugin JAR via {@link PluginLoader}.</li>
 *   <li>Delegate plugin start and HTTP server startup to {@link Application}.</li>
 *   <li>If {@code --gui} was passed, open the {@link IndexerGui} on the EDT.</li>
 * </ol>
 *
 * <p>Any failure in steps 1–5 is treated as fatal: a message is logged and the JVM exits
 * with status 1.
 */
public final class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    private Main() {}

    public static void main(String[] args) {
        boolean guiRequested = Arrays.asList(args).contains("--gui");

        try {
            AppConfig config = ConfigLoader.load();
            ConnectionPool pool = new ConnectionPool(config.getDatabase());
            new DatabaseInitializer(pool).init();

            MetricsCollector metrics = MetricsCollector.initialize(pool);
            metrics.start();

            WebhookRouterImpl router = new WebhookRouterImpl();
            PublisherRegistry registry = new PublisherRegistry();

            PluginLoader pluginLoader = new PluginLoader(config);
            ProviderPlugin plugin = pluginLoader.load();

            Application app = new Application(config, pool, plugin, router, registry, metrics);
            app.start();
            app.registerShutdownHook(pluginLoader, pool, metrics);
            log.info("Central Indexer started on port {}", app.getPort());

            if (guiRequested) {
                launchGui(pool, metrics);
            }

        } catch (Exception e) {
            log.error("Fatal startup error — exiting", e);
            System.exit(1);
        }
    }

    private static void launchGui(ConnectionPool pool, MetricsCollector metrics) {
        SwingUtilities.invokeLater(() -> {
            ThemeManager.getInstance().setDarkTheme();

            ReviewsIndexRepository reviewsRepo   = new ReviewsIndexRepository(pool);
            BranchRepository       branchRepo    = new BranchRepository(pool);
            RepositoriesRepository reposRepo     = new RepositoriesRepository(pool);

            IndexerGui gui = new IndexerGui(metrics, reviewsRepo, branchRepo, reposRepo);
            gui.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            gui.setVisible(true);
            gui.startRefresh();
        });
    }
}
