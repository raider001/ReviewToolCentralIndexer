package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.config.TlsConfig;
import com.kalynx.centralindexer.exception.TlsConfigurationException;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.KeyStore;

/**
 * Utility class responsible for creating the appropriate {@link HttpServer} or
 * {@link HttpsServer} based on the supplied {@link TlsConfig}.
 *
 * <p>When {@code tls} is {@code null} or {@link TlsConfig#isEnabled()} returns
 * {@code false}, a plain {@link HttpServer} is returned (behaviour 9.1).
 *
 * <p>When TLS is enabled, a {@link HttpsServer} is returned with an {@link SSLContext}
 * loaded from the configured keystore (behaviour 9.2). Any failure to load the keystore —
 * including a missing file or incorrect password — causes an immediate
 * {@link TlsConfigurationException} whose message contains the configured path
 * (behaviours 9.3 and 9.4).
 */
public final class TlsConfigurator {

    private TlsConfigurator() {
    }

    /**
     * Creates and binds an HTTP server on the given port.
     *
     * @param port the port to bind; use {@code 0} for an OS-assigned port
     * @param tls  the TLS configuration, or {@code null} to use plain HTTP
     * @return a bound {@link HttpServer} (or {@link HttpsServer} when TLS is enabled)
     * @throws IOException               if the server socket cannot be bound
     * @throws TlsConfigurationException if TLS is enabled and the keystore cannot be loaded
     */
    public static HttpServer createServer(int port, TlsConfig tls) throws IOException {
        if (tls == null || !tls.isEnabled()) {
            return HttpServer.create(new InetSocketAddress(port), 0);
        }
        return createHttpsServer(port, tls);
    }

    private static HttpsServer createHttpsServer(int port, TlsConfig tls) throws IOException {
        SSLContext sslContext = loadSslContext(tls);
        HttpsServer httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        return httpsServer;
    }

    private static SSLContext loadSslContext(TlsConfig tls) {
        try {
            KeyStore ks = KeyStore.getInstance(tls.getKeystoreType());
            char[] password = tls.getKeystorePassword().toCharArray();
            try (FileInputStream fis = new FileInputStream(tls.getKeystorePath())) {
                ks.load(fis, password);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, password);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (Exception e) {
            throw new TlsConfigurationException(
                    "Failed to configure TLS with keystore '" + tls.getKeystorePath()
                    + "': " + e.getMessage(), e);
        }
    }
}

