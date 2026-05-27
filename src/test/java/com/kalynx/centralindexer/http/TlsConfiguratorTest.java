package com.kalynx.centralindexer.http;

import com.kalynx.centralindexer.config.TlsConfig;
import com.kalynx.centralindexer.exception.TlsConfigurationException;
import com.kalynx.centralindexer.it.support.TestKeystore;
import com.kalynx.centralindexer.json.GsonFactory;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TlsConfigurator}.
 *
 * <p>Verifies that the correct server type is created based on the {@link TlsConfig} state,
 * and that invalid keystores cause {@link TlsConfigurationException} with the expected message.
 */
class TlsConfiguratorTest {

    @Test
    void createServer_tlsDisabled_returnsPlainHttpServer() throws Exception {
        TlsConfig tls = tlsConfig(false, "/some/path", "password", "PKCS12");
        HttpServer server = TlsConfigurator.createServer(0, tls);
        try {
            assertFalse(server instanceof HttpsServer,
                    "TLS disabled must return plain HttpServer, not HttpsServer");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createServer_nullTlsConfig_returnsPlainHttpServer() throws Exception {
        HttpServer server = TlsConfigurator.createServer(0, null);
        try {
            assertFalse(server instanceof HttpsServer,
                    "Null TLS config must return plain HttpServer, not HttpsServer");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void createServer_tlsEnabled_returnsHttpsServer() throws Exception {
        try (TestKeystore ks = new TestKeystore()) {
            TlsConfig tls = tlsConfig(true, ks.getKeystorePath(), ks.getPassword(), ks.getKeystoreType());
            HttpServer server = TlsConfigurator.createServer(0, tls);
            try {
                assertInstanceOf(HttpsServer.class, server,
                        "TLS enabled must return HttpsServer");
                HttpsServer httpsServer = (HttpsServer) server;
                assertNotNull(httpsServer.getHttpsConfigurator(),
                        "HttpsServer must have an HttpsConfigurator");
                assertNotNull(httpsServer.getHttpsConfigurator().getSSLContext(),
                        "HttpsConfigurator must have a non-null SSLContext");
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void createServer_missingKeystore_throwsTlsConfigurationException() {
        String missingPath = "/nonexistent/path/test-keystore.p12";
        TlsConfig tls = tlsConfig(true, missingPath, "password", "PKCS12");
        TlsConfigurationException ex = assertThrows(TlsConfigurationException.class,
                () -> TlsConfigurator.createServer(0, tls));
        assertTrue(ex.getMessage().contains(missingPath),
                "Exception message must contain the configured keystore path");
    }

    @Test
    void createServer_wrongPassword_throwsTlsConfigurationException() throws Exception {
        try (TestKeystore ks = new TestKeystore()) {
            TlsConfig tls = tlsConfig(true, ks.getKeystorePath(), "wrong-password-xyz", ks.getKeystoreType());
            assertThrows(TlsConfigurationException.class,
                    () -> TlsConfigurator.createServer(0, tls),
                    "Incorrect keystore password must throw TlsConfigurationException");
        }
    }

    private TlsConfig tlsConfig(boolean enabled, String keystorePath, String password, String type) {
        String pathJson = GsonFactory.getInstance().toJson(keystorePath);
        String json = String.format(
                "{\"enabled\":%b,\"keystorePath\":%s,\"keystorePassword\":\"%s\",\"keystoreType\":\"%s\"}",
                enabled, pathJson, password, type);
        return GsonFactory.getInstance().fromJson(json, TlsConfig.class);
    }
}

