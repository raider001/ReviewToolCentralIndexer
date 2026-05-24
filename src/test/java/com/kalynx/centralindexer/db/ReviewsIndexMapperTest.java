package com.kalynx.centralindexer.db;

import com.google.gson.Gson;
import com.kalynx.centralindexer.json.GsonFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewsIndexMapperTest {

    private final Gson gson = GsonFactory.getInstance();

    @Test
    void toRepositoriesJsonProducesDeterministicOrderingAndShape() {
        ReviewsIndexMapper.RepoEntry a = new ReviewsIndexMapper.RepoEntry(
                "alice", "repo-a", "https://example/alice/repo-a", "develop", "deadbeef");
        ReviewsIndexMapper.RepoEntry b = new ReviewsIndexMapper.RepoEntry(
                "alice", "repo-a", "https://example/alice/repo-a", "main", "cafebabe");
        ReviewsIndexMapper.RepoEntry c = new ReviewsIndexMapper.RepoEntry(
                "bob", "repo-b", "https://example/bob/repo-b", "feature", null);

        // deliberately unordered input
        String json = ReviewsIndexMapper.toRepositoriesJson(List.of(c, a, b));

        List<Map<String, Object>> parsed = gson.fromJson(json, List.class);
        assertEquals(3, parsed.size(), "Three entries expected in JSON array");

        // sorted by owner (alice..bob) and branch name (develop before main)
        assertEquals("alice", parsed.get(0).get("owner"));
        assertEquals("repo-a", parsed.get(0).get("repository"));
        assertEquals("https://example/alice/repo-a", parsed.get(0).get("repositoryUrl"));
        assertEquals("develop", parsed.get(0).get("branchName"));
        assertEquals("deadbeef", parsed.get(0).get("headCommit"));

        assertEquals("alice", parsed.get(1).get("owner"));
        assertEquals("main", parsed.get(1).get("branchName"));
        assertEquals("cafebabe", parsed.get(1).get("headCommit"));

        assertEquals("bob", parsed.get(2).get("owner"));
        assertEquals("feature", parsed.get(2).get("branchName"));
        assertEquals(null, parsed.get(2).get("headCommit"));
    }
}

