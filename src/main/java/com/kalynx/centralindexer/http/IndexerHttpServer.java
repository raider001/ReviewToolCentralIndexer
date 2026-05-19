package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.exception.TlsConfigurationException;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP(S) server for the Central Indexer.
 *
 * <p>Binds on {@code server.port} (default 8765) using a virtual-thread-per-task executor.
 * Three contexts are registered:
 * <ul>
 *   <li>{@code /health} - database health check, auth bypassed always</li>
 *   <li>{@code /webhooks/} - plugin webhook dispatch, auth bypassed always</li>
 *   <li>{@code /events} - guarded by {@link AuthFilter}</li>
 * </ul>
 *
 * <p>When {@code server.tls.enabled} is {@code false} or the {@code tls} block is absent,
 * a plain {@link HttpServer} is used. When {@code true}, an {@code HttpsServer} is
 * configured by {@link TlsConfigurator} from the supplied keystore.
 */
public final class IndexerHttpServer {

    private final HttpServer server;

    /**
     * Creates and configures the HTTP(S) server. Does not start accepting connections until
     * {@link #start()} is called.
     *
     * @param config     the application configuration
     * @param pool       the connection pool for health checks
     * @param router     the webhook router populated by the provider plugin
     * @param repository the event repository for SSE replay and cursor validation
     * @param registry   the publisher registry for live SSE fan-out
     * @throws IOException                if the server socket cannot be bound
     * @throws TlsConfigurationException  if TLS is enabled and the keystore cannot be loaded
     */
    public IndexerHttpServer(AppConfig config, ConnectionPool pool, WebhookRouterImpl router,
                             EventRepository repository, PublisherRegistry registry)
            throws IOException {
        server = createServer(config);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        registerHandlers(config, pool, router, repository, registry);
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
                                  EventRepository repository, PublisherRegistry registry) {
        server.createContext("/health", new HealthHandler(pool));
        server.createContext("/webhooks/", new WebhookDispatcher(router));
        if (repository != null && registry != null) {
            server.createContext("/events/stream",
                    new AuthFilter(config.getAuth(), new SseHandler(repository, registry)));
        }
        server.createContext("/events", new AuthFilter(config.getAuth(), new EventsHandler(repository)));
    }
}

