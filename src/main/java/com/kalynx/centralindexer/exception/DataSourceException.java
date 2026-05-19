package com.kalynx.centralindexer.exception;

/**
 * Thrown when the {@link com.kalynx.centralindexer.db.ConnectionPool} cannot establish
 * JDBC connections to the configured PostgreSQL instance at startup.
 *
 * <p>The indexer fails immediately on this exception — there is no retry loop.
 * Recovery is delegated to the process supervisor (e.g. Docker {@code restart: unless-stopped}).
 */
public final class DataSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Constructs a {@code DataSourceException} with a detail message and cause.
     *
     * @param message description of the failure
     * @param cause   the underlying {@link java.sql.SQLException}
     */
    public DataSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}


