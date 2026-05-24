package com.kalynx.centralindexer.config;

/**
 * Configuration entry for a single tracked repository.
 *
 * <p>Deserialized from each element of the {@code repositories} array in {@code config.json}.
 * Example entry:
 * <pre>{@code
 * {
 *   "owner":      "my-org",
 *   "repository": "my-repo",
 *   "url":        "https://github.com/my-org/my-repo"
 * }
 * }</pre>
 *
 * <p>All three fields are required. The {@code url} is used to seed the {@code repositories}
 * DB table at startup so the startup reconciler can run and so that branch webhooks can satisfy
 * the FK constraint.
 */
public final class RepositoryConfig {

    private final String owner;
    private final String repository;
    private final String url;

    RepositoryConfig(String owner, String repository, String url) {
        this.owner = owner;
        this.repository = repository;
        this.url = url;
    }

    public String getOwner()      { return owner; }
    public String getRepository() { return repository; }
    public String getUrl()        { return url; }

    /** Returns the canonical {@code owner/repository} identifier used by the plugin API. */
    public String ownerSlashRepo() {
        return owner + "/" + repository;
    }
}
