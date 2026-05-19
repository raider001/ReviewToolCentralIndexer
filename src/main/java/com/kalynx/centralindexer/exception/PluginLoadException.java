package com.kalynx.centralindexer.exception;

import java.io.Serial;

/**
 * Thrown when the plugin system cannot load, validate, or configure the provider plugin.
 *
 * <p>Common causes: zero plugin implementations found, more than one found, the loaded
 * plugin's {@code providerId()} does not match the value in {@code config.json}, or the
 * plugins directory cannot be read.
 */
public final class PluginLoadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a {@code PluginLoadException} with a detail message.
     *
     * @param message description of the failure
     */
    public PluginLoadException(String message) {
        super(message);
    }

    /**
     * Constructs a {@code PluginLoadException} with a detail message and cause.
     *
     * @param message description of the failure
     * @param cause   the underlying exception
     */
    public PluginLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}

