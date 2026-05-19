package com.kalynx.centralindexer.config;

/**
 * PostgreSQL connection configuration for the indexer's connection pool.
 */
public final class DatabaseConfig {

    private String url;
    private String user;
    private String password;
    private int poolSize = 20;

    /**
     * Returns the JDBC URL (e.g. {@code jdbc:postgresql://localhost:5432/indexer}).
     *
     * @return the JDBC connection URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns the database username.
     *
     * @return the username
     */
    public String getUser() {
        return user;
    }

    /**
     * Returns the database password.
     *
     * @return the password
     */
    public String getPassword() {
        return password;
    }

    /**
     * Returns the number of JDBC connections to open in the connection pool.
     *
     * @return the pool size (default 20)
     */
    public int getPoolSize() {
        return poolSize;
    }
}

