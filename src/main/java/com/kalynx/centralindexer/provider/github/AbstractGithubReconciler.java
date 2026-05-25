package com.kalynx.centralindexer.provider.github;

import com.kalynx.centralindexer.provider.Reconciler;

public class AbstractGithubReconciler implements Reconciler {
    @Override
    public String getApiUrl() {
        return "https://api.github.com";
    }

}
