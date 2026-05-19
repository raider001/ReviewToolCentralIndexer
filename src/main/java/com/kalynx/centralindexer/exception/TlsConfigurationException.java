package com.kalynx.centralindexer.exception;

/**
 * Thrown when the TLS configuration is invalid or the keystore cannot be loaded.
 *
 * <p>This exception is thrown during server startup when {@code server.tls.enabled} is
 * {@code true} and the configured keystore path is missing, unreadable, or the supplied
 * password is incorrect.
 */
public final class TlsConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a {@code TlsConfigurationException} with the given detail message.
     *
     * @param message description of the configuration error
     */
    public TlsConfigurationException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code TlsConfigurationException} with the given detail message and cause.
     *
     * @param message description of the configuration error
     * @param cause   the underlying exception
     */
    public TlsConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}

