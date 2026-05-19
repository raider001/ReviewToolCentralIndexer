package com.kalynx.centralindexer.exception;

/**
 * Thrown when the application configuration file cannot be located, read, or parsed.
 */
public final class ConfigLoadException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a {@code ConfigLoadException} with a detail message.
     *
     * @param message description of the failure
     */
    public ConfigLoadException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code ConfigLoadException} with a detail message and cause.
     *
     * @param message description of the failure
     * @param cause   the underlying exception
     */
    public ConfigLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}


