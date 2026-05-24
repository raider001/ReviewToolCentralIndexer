package com.kalynx.centralindexer.db;

import com.google.gson.Gson;
import com.kalynx.centralindexer.json.GsonFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Utility that maps repository/branch information to the compact JSON shape stored in
 * the {@code reviews_index.repositories} JSONB column.
 */
public final class ReviewsIndexMapper {

    private static final Gson GSON = GsonFactory.getInstance();

    private ReviewsIndexMapper() {
    }

    /**
     * Lightweight value carrier describing a single repository+branch entry.
     */
    public static final class RepoEntry {
        public final String owner;
        public final String repository;
        public final String repositoryUrl;
        public final String branchName;
        public final String headCommit;

        public RepoEntry(String owner, String repository, String repositoryUrl, String branchName, String headCommit) {
            this.owner = owner;
            this.repository = repository;
            this.repositoryUrl = repositoryUrl;
            this.branchName = branchName;
            this.headCommit = headCommit;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            RepoEntry repoEntry = (RepoEntry) o;
            return Objects.equals(owner, repoEntry.owner) &&
                    Objects.equals(repository, repoEntry.repository) &&
                    Objects.equals(repositoryUrl, repoEntry.repositoryUrl) &&
                    Objects.equals(branchName, repoEntry.branchName) &&
                    Objects.equals(headCommit, repoEntry.headCommit);
        }

        @Override
        public int hashCode() {
            return Objects.hash(owner, repository, repositoryUrl, branchName, headCommit);
        }
    }

    /**
     * Produces the compact repositories JSON array used by {@code reviews_index}.
     * The result is deterministic: entries are sorted by owner, repository and branch name.
     *
     * @param entries repository/branch entries
     * @return JSON array string (not pretty-printed)
     */
    public static String toRepositoriesJson(List<RepoEntry> entries) {
        List<RepoEntry> copy = new ArrayList<>(entries);
        copy.sort(Comparator.comparing((RepoEntry e) -> safe(e.owner))
                .thenComparing(e -> safe(e.repository))
                .thenComparing(e -> safe(e.branchName)));
        return GSON.toJson(copy);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}

