package com.kalynx.centralindexer.config;

/**
 * Optional TLS configuration for the embedded HTTP server.
 *
 * <p>When {@link #isEnabled()} returns {@code false} (the default), the server binds plain
 * HTTP. When {@code true}, an {@code HttpsServer} is created using the specified keystore.
 */
public final class TlsConfig {

    private boolean enabled = false;
    private String keystorePath;
    private String keystorePassword;
    private String keystoreType = "PKCS12";

    /**
     * Returns whether TLS termination is enabled.
     *
     * @return {@code true} if HTTPS mode should be used
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the filesystem path to the keystore file.
     *
     * @return the keystore path
     */
    public String getKeystorePath() {
        return keystorePath;
    }

    /**
     * Returns the keystore password.
     *
     * @return the password used to open the keystore
     */
    public String getKeystorePassword() {
        return keystorePassword;
    }

    /**
     * Returns the keystore type (e.g. {@code "PKCS12"}, {@code "JKS"}).
     *
     * @return the keystore type
     */
    public String getKeystoreType() {
        return keystoreType;
    }
}

