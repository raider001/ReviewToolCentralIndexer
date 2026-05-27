package com.kalynx.centralindexer.provider.github;

final class GitHubConstants {

    static final String GITHUB_API_BASE_URL = "https://api.github.com";
    static final String GITHUB_BASE_URL = "https://github.com/";

    static final String PROP_API_TOKEN = "apiToken";
    static final String PROP_REVIEWS_BRANCH_NAME = "reviewsBranchName";
    static final String DEFAULT_REVIEWS_BRANCH_NAME = "kalynx-reviews";

    static final String ACCEPT_HEADER = "application/vnd.github+json";
    static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    static final String API_VERSION = "2022-11-28";
    static final String BEARER_PREFIX = "Bearer ";

    static boolean isTokenMissing(String token) {
        return token == null || token.isBlank();
    }

    private GitHubConstants() {}
}
