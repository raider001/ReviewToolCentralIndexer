package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.db.BranchRepository;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.RepositoriesRepository;
import com.kalynx.centralindexer.db.ReviewsIndexRepository;
import com.kalynx.centralindexer.exception.TlsConfigurationException;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP(S) server for the Central Indexer.
 *
 * <p>Binds on {@code server.port} (default 8765) using a virtual-thread-per-task executor.
 * Six contexts are registered (three optional, conditional on their dependency being non-null):
 * <ul>
 *   <li>{@code /health} — database health check, auth bypassed always</li>
 *   <li>{@code /webhooks/} — plugin webhook dispatch, auth bypassed always</li>
 *   <li>{@code /metrics} — operational metrics snapshot, auth bypassed always</li>
 *   <li>{@code /events/stream} — live SSE stream, guarded by {@link AuthFilter}</li>
 *   <li>{@code /branches} — branch typeahead, guarded by {@link AuthFilter}</li>
 *   <li>{@code /reviews} — review index query, guarded by {@link AuthFilter}</li>
 *   <li>{@code /repositories} — repository registration, guarded by {@link AuthFilter}</li>
 * </ul>
 *
 * <p>When {@code server.tls.enabled} is {@code false} or the {@code tls} block is absent,
 * a plain {@link HttpServer} is used. When {@code true}, an {@code HttpsServer} is
 * configured by {@link TlsConfigurator} from the supplied keystore.
 */
public final class IndexerHttpServer {

    private final HttpServer server;

    /**
     * Convenience overload that skips the {@code /branches} and {@code /reviews} contexts.
     */
    public IndexerHttpServer(AppConfig config, ConnectionPool pool, WebhookRouterImpl router,
                             PublisherRegistry registry) throws IOException {
        this(config, pool, router, registry, null, null, null, new MetricsCollector(pool));
    }

    /**
     * Convenience overload that skips the {@code /reviews} context.
     */
    public IndexerHttpServer(AppConfig config, ConnectionPool pool, WebhookRouterImpl router,
                             PublisherRegistry registry, BranchRepository branchRepository)
            throws IOException {
        this(config, pool, router, registry, branchRepository, null, null, new MetricsCollector(pool));
    }

    /**
     * Convenience overload that skips the {@code /repositories} context.
     */
    public IndexerHttpServer(AppConfig config, ConnectionPool pool, WebhookRouterImpl router,
                             PublisherRegistry registry, BranchRepository branchRepository,
                             ReviewsIndexRepository reviewsRepository)
            throws IOException {
        this(config, pool, router, registry, branchRepository, reviewsRepository, null,
                new MetricsCollector(pool));
    }

    /**
     * Convenience overload with a caller-supplied {@link MetricsCollector} and no
     * {@code /repositories} context.
     */
    public IndexerHttpServer(AppConfig config, ConnectionPool pool, WebhookRouterImpl router,
                             PublisherRegistry registry, BranchRepository branchRepository,
                             ReviewsIndexRepository reviewsRepository,
                             RepositoriesRepository repositoriesRepository)
            throws IOException {
        this(config, pool, router, registry, branchRepository, reviewsRepository,
                repositoriesRepository, new MetricsCollector(pool));
    }

    /**
     * Creates and configures the HTTP(S) server. Does not start accepting connections until
     * {@link #start()} is called.
     *
     * @param config                   the application configuration
     * @param pool                     the connection pool for health checks
     * @param router                   the webhook router populated by the provider plugin
     * @param registry                 the publisher registry for live SSE fan-out; may be {@code null}
     *                                 to skip registering the SSE endpoint
     * @param branchRepository         the branch query repository; may be {@code null} to skip
     *                                 registering the {@code /branches} endpoint
     * @param reviewsRepository        the reviews index repository; may be {@code null} to skip
     *                                 registering the {@code /reviews} endpoint
     * @param repositoriesRepository   the repositories repository; may be {@code null} to skip
     *                                 registering the {@code /repositories} endpoint
     * @param metrics                  the metrics collector shared with the GUI (if running)
     * @throws IOException               if the server socket cannot be bound
     * @throws TlsConfigurationException if TLS is enabled and the keystore cannot be loaded
     */
    public IndexerHttpServer(AppConfig config, ConnectionPool pool, WebhookRouterImpl router,
                             PublisherRegistry registry, BranchRepository branchRepository,
                             ReviewsIndexRepository reviewsRepository,
                             RepositoriesRepository repositoriesRepository,
                             MetricsCollector metrics)
            throws IOException {
        server = createServer(config);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        registerHandlers(config, pool, router, registry, branchRepository, reviewsRepository,
                repositoriesRepository, metrics);
    }

    /**
     * Starts accepting incoming connections.
     */
    public void start() {
        server.start();
    }

    /**
     * Stops the server, allowing up to 1 second for in-flight requests to complete.
     */
    public void stop() {
        server.stop(1);
    }

    /**
     * Returns the local port that the server is bound to.
     *
     * <p>Useful when the server is started on port {@code 0} (OS-assigned random port).
     *
     * @return the actual bound port number
     */
    public int getPort() {
        return server.getAddress().getPort();
    }

    private HttpServer createServer(AppConfig config) throws IOException {
        return TlsConfigurator.createServer(
                config.getServer().getPort(),
                config.getServer().getTls());
    }

    private void registerHandlers(AppConfig config, ConnectionPool pool, WebhookRouterImpl router,
                                  PublisherRegistry registry, BranchRepository branchRepository,
                                  ReviewsIndexRepository reviewsRepository,
                                  RepositoriesRepository repositoriesRepository,
                                  MetricsCollector metrics) {
        server.createContext("/health",   new HealthHandler(pool));
        server.createContext("/webhooks/", new WebhookDispatcher(router));
        server.createContext("/metrics",   new MetricsHandler(metrics));
        if (registry != null) {
            server.createContext("/events/stream",
                    new AuthFilter(config.getAuth(), new SseHandler(registry, metrics)));
        }
        if (branchRepository != null) {
            server.createContext("/branches",
                    new AuthFilter(config.getAuth(), new BranchesHandler(branchRepository, metrics)));
        }
        if (reviewsRepository != null) {
            server.createContext("/reviews",
                    new AuthFilter(config.getAuth(), new ReviewsHandler(reviewsRepository, metrics)));
        }
        if (repositoriesRepository != null) {
            server.createContext("/repositories",
                    new AuthFilter(config.getAuth(), new RepositoriesHandler(repositoriesRepository)));
        }
    }
}
