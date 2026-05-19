package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.config.AppConfig;
import com.kalynx.centralindexer.config.TlsConfig;
import com.kalynx.centralindexer.db.ConnectionPool;
import com.kalynx.centralindexer.db.EventRepository;
import com.kalynx.centralindexer.exception.TlsConfigurationException;
import com.kalynx.centralindexer.plugin.WebhookRouterImpl;
import com.kalynx.centralindexer.sse.PublisherRegistry;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyStore;
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
 * a plain {@link HttpServer} is used. When {@code true}, an {@link HttpsServer} is
 * configured from the supplied keystore (behavior 4.10; full test coverage in Milestone 9).
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
        int port = config.getServer().getPort();
        TlsConfig tls = config.getServer().getTls();
        if (tls != null && tls.isEnabled()) {
            return createHttpsServer(port, tls);
        }
        return HttpServer.create(new InetSocketAddress(port), 0);
    }

    private HttpsServer createHttpsServer(int port, TlsConfig tls) throws IOException {
        try {
            KeyStore ks = KeyStore.getInstance(tls.getKeystoreType());
            char[] password = tls.getKeystorePassword().toCharArray();
            try (FileInputStream fis = new FileInputStream(tls.getKeystorePath())) {
                ks.load(fis, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);
            HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            return httpsServer;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new TlsConfigurationException(
                    "Failed to configure TLS with keystore '" + tls.getKeystorePath() + "': "
                    + e.getMessage(), e);
        }
    }

    private void registerHandlers(AppConfig config, ConnectionPool pool, WebhookRouterImpl router,
                                  EventRepository repository, PublisherRegistry registry) {
        server.createContext("/health", new HealthHandler(pool));
        server.createContext("/webhooks/", new WebhookDispatcher(router));
        if (repository != null && registry != null) {
            server.createContext("/events/stream",
                    new AuthFilter(config.getAuth(), new SseHandler(repository, registry)));
        }
        HttpHandler eventsStub = exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.getResponseBody().close();
        };
        server.createContext("/events", new AuthFilter(config.getAuth(), eventsStub));
    }
}

