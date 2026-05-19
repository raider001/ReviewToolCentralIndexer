package com.kalynx.centralindexer.config;

/**
 * Configuration for the embedded HTTP server.
 */
public final class ServerConfig {

    private int port = 8765;
    private TlsConfig tls;

    /**
     * Returns the TCP port the HTTP server will bind to.
     *
     * @return the configured port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the optional TLS configuration.
     *
     * @return the TLS config, or {@code null} when TLS is not configured
     */
    public TlsConfig getTls() {
        return tls;
    }
}

