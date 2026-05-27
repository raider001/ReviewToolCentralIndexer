package com.kalynx.centralindexer.provider.github;

import com.kalynx.centralindexer.provider.Reconciler;

public class AbstractGithubReconciler implements Reconciler {
    @Override
    public String getApiUrl() {
        return GitHubConstants.GITHUB_API_BASE_URL;
    }

}
