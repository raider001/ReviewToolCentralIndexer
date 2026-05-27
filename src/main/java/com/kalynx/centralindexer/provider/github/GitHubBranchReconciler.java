package com.kalynx.centralindexer.provider.github;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kalynx.centralindexer.metrics.MetricsCollector;
import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.provider.common.ReviewRefParser;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Commit-based reconciliation for the {@code refs/heads/kalynx-reviews} orphan branch on GitHub.
 *
 * <p>Two operations are provided:
 * <ul>
 *   <li>{@link #fetchKalynxReviewHead} — reads the current HEAD SHA of
 *       {@code refs/heads/kalynx-reviews} via the GitHub Git Refs API.</li>
 *   <li>{@link #reconcileFromCommit} — uses the GitHub Compare API to enumerate all files
 *       changed between two commit SHAs on that branch, parses each file path as
 *       {@code reviews/{reviewId}/{streamName}}, and submits a {@link ReviewEvent} per
 *       affected review via the supplied {@link EventSink}.</li>
 * </ul>
 *
 * <p>Configuration keys read from {@link ProviderConfig#properties()}:
 * <ul>
 *   <li>{@code apiToken} — GitHub personal access token (required)</li>
 * </ul>
 */
final class GitHubBranchReconciler extends AbstractGithubReconciler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubBranchReconciler.class);
    private static final String REVIEWS_PATH_PREFIX = "reviews/";
    private static final int COMPARE_FILE_LIMIT = 250;

    private final HttpClient http;
    private final MetricsCollector metrics;

    GitHubBranchReconciler(MetricsCollector metrics) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.metrics = metrics;
    }

    GitHubBranchReconciler() {
        this((MetricsCollector) null);
    }

    GitHubBranchReconciler(HttpClient http, MetricsCollector metrics) {
        this.http = http;
        this.metrics = metrics;
    }

    /**
     * Returns the current HEAD commit SHA of {@code refs/heads/kalynx-reviews} for the
     * given repository, or {@code null} if the branch does not exist or the request fails.
     *
     * @param repository the canonical {@code owner/repo} identifier
     * @param config     the provider configuration (for the API token)
     * @return the 40-character commit SHA, or {@code null}
     */
    String fetchKalynxReviewHead(String repository, ProviderConfig config) {
        String token = config.properties().get(GitHubConstants.PROP_API_TOKEN);
        if (GitHubConstants.isTokenMissing(token)) {
            LOGGER.warn("No apiToken configured; cannot fetch kalynx-reviews HEAD for {}", repository);
            return null;
        }
        String branchName = config.properties().getOrDefault(
                GitHubConstants.PROP_REVIEWS_BRANCH_NAME, GitHubConstants.DEFAULT_REVIEWS_BRANCH_NAME);
        String url = getApiUrl() + "/repos/" + repository + "/git/ref/heads/" + branchName;
        try {
            HttpRequest request = buildRequest(url, token);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (metrics != null) metrics.recordProviderApiCall();
            if (response.statusCode() == 401) {
                LOGGER.warn("GitHub Git Refs API returned 401 Unauthorized for {} — " +
                         "verify that 'apiToken' in plugin configuration is valid and not expired", repository);
                return null;
            }
            if (response.statusCode() == 404) {
                LOGGER.debug("kalynx-reviews branch not found for {}", repository);
                return null;
            }
            if (response.statusCode() != 200) {
                LOGGER.warn("GitHub Git Refs API returned {} for {}", response.statusCode(), repository);
                return null;
            }
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            return json.getAsJsonObject("object").get("sha").getAsString();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("Error fetching kalynx-reviews HEAD for {}: {} — {}",
                    repository, e.getClass().getSimpleName(), e.getMessage());
            LOGGER.debug("Full exception for {}", repository, e);
            return null;
        }
    }

    /**
     * Replays review events for all files changed between {@code fromCommit} (exclusive) and
     * {@code toCommit} (inclusive) on {@code refs/heads/kalynx-reviews}.
     *
     * <p>Uses the GitHub Compare API ({@code GET /repos/{owner}/{repo}/compare/{from}...{to}})
     * which returns the net set of files changed across the entire commit range. Each file whose
     * path matches {@code reviews/{reviewId}/{streamName}} produces one {@link ReviewEvent}.
     * The GitHub Compare API returns at most {@value #COMPARE_FILE_LIMIT} files; a warning is
     * LOGGERged if the range is larger.
     *
     * @param repository the canonical {@code owner/repo} identifier
     * @param fromCommit the exclusive lower-bound commit SHA (the stored cursor)
     * @param toCommit   the inclusive upper-bound commit SHA (the live HEAD)
     * @param config     the provider configuration (for the API token)
     * @param sink       the event sink to receive the reconciled events
     */
    boolean reconcileFromCommit(String repository, String fromCommit, String toCommit,
                                ProviderConfig config, EventSink sink) {
        String token = config.properties().get(GitHubConstants.PROP_API_TOKEN);
        if (GitHubConstants.isTokenMissing(token)) {
            LOGGER.warn("No apiToken configured; cannot reconcile {} from commit", repository);
            return false;
        }
        String url = getApiUrl() + "/repos/" + repository + "/compare/" + fromCommit + "..." + toCommit;
        try {
            HttpRequest request = buildRequest(url, token);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (metrics != null) metrics.recordProviderApiCall();
            if (response.statusCode() != 200) {
                LOGGER.warn("GitHub Compare API returned {} for {} ({}...{})",
                        response.statusCode(), repository,
                        abbrev(fromCommit), abbrev(toCommit));
                return false;
            }
            JsonObject compare = JsonParser.parseString(response.body()).getAsJsonObject();
            LOGGERCompareStats(compare, repository, fromCommit, toCommit);
            emitEventsFromFiles(compare, repository, toCommit, token, sink);
            return true;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.warn("Error reconciling {} ({}...{}): {} — {}",
                    repository, abbrev(fromCommit), abbrev(toCommit),
                    e.getClass().getSimpleName(), e.getMessage());
            LOGGER.debug("Full exception for {}", repository, e);
            return false;
        }
    }

    /**
     * Pages through the GitHub Branches API and emits a {@code BRANCH_UPDATED} event for
     * every branch currently present in the repository.
     *
     * @param repository    the canonical {@code owner/repo} identifier
     * @param config        the provider configuration (for the API token)
     * @param sink          the event sink to receive the branch events
     */
    void reconcileAllBranches(String repository, ProviderConfig config, EventSink sink) {
        String token = config.properties().get(GitHubConstants.PROP_API_TOKEN);
        if (GitHubConstants.isTokenMissing(token)) {
            LOGGER.warn("No apiToken configured; cannot reconcile branches for {}", repository);
            return;
        }
        String repositoryUrl = GitHubConstants.GITHUB_BASE_URL + repository;
        int page = 1;
        int total = 0;
        while (true) {
            String url = getApiUrl() + "/repos/" + repository + "/branches?per_page=100&page=" + page;
            String body = fetchGet(url, token);
            if (body == null) break;
            JsonArray branches = JsonParser.parseString(body).getAsJsonArray();
            if (branches.isEmpty()) break;
            for (JsonElement el : branches) {
                JsonObject branch = el.getAsJsonObject();
                String name = branch.get("name").getAsString();
                String sha  = branch.getAsJsonObject("commit").get("sha").getAsString();
                ReviewEvent event = new ReviewEvent(0L, Instant.now(), repository,
                        EventType.BRANCH_UPDATED, null, null, null,
                        Map.of("branch_name", name, "head_commit", sha,
                               "repository_url", repositoryUrl));
                try {
                    sink.submit(event);
                    total++;
                } catch (RuntimeException e) {
                    LOGGER.warn("Failed to submit branch event for {}/{}: {}",
                            repository, name, e.getMessage());
                }
            }
            page++;
        }
        LOGGER.info("Reconciled {} branch(es) for {}", total, repository);
    }

    /**
     * Fetches the full recursive tree of {@code refs/heads/kalynx-reviews} at
     * {@code headCommit} via the GitHub Trees API and emits a review event for every
     * file matching {@code reviews/{reviewId}/{streamName}}.
     *
     * <p>Used on the very first startup (when no cursor has been stored) to perform
     * a complete initial index of all existing reviews.
     *
     * @param repository  the canonical {@code owner/repo} identifier
     * @param headCommit  the HEAD SHA of {@code refs/heads/kalynx-reviews}
     * @param config      the provider configuration (for the API token)
     * @param sink        the event sink to receive the review events
     */
    boolean reconcileFullReviewTree(String repository, String headCommit,
                                     ProviderConfig config, EventSink sink) {
        String token = config.properties().get(GitHubConstants.PROP_API_TOKEN);
        if (GitHubConstants.isTokenMissing(token)) {
            LOGGER.warn("No apiToken configured; cannot reconcile full review tree for {}", repository);
            return false;
        }
        String url = getApiUrl() + "/repos/" + repository + "/git/trees/" + headCommit + "?recursive=1";
        String body = fetchGet(url, token);
        if (body == null) return false;

        JsonObject treeResponse = JsonParser.parseString(body).getAsJsonObject();
        if (treeResponse.has("truncated") && treeResponse.get("truncated").getAsBoolean()) {
            LOGGER.warn("GitHub Trees API response for {} is truncated — review index may be incomplete",
                    repository);
        }

        JsonArray tree = treeResponse.getAsJsonArray("tree");
        if (tree == null || tree.isEmpty()) {
            LOGGER.debug("kalynx-reviews tree is empty for {}", repository);
            return true;
        }

        // Sort paths so that metadata/title appears before other streams for each review,
        // giving deterministic REVIEW_CREATED detection.
        Map<String, String> sortedPaths = new TreeMap<>();
        // Collect blob SHAs for fields we need to hydrate.
        Map<String, String> statusShas = new HashMap<>();
        Map<String, String> branchShas = new HashMap<>();
        for (JsonElement el : tree) {
            JsonObject file = el.getAsJsonObject();
            if (!"blob".equals(file.get("type").getAsString())) continue;
            String path = file.get("path").getAsString();
            sortedPaths.put(path, "");
            if (!path.startsWith(REVIEWS_PATH_PREFIX)) continue;
            String rest = path.substring(REVIEWS_PATH_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash < 0) continue;
            String reviewId   = rest.substring(0, slash);
            String streamPath = rest.substring(slash + 1);
            String sha = file.get("sha").getAsString();
            if ("metadata/status".equals(streamPath)) statusShas.put(reviewId, sha);
            else if ("metadata/branch".equals(streamPath)) branchShas.put(reviewId, sha);
        }

        // Fetch status and branch values (one blob fetch per review that has them).
        Map<String, String> reviewStatuses = new HashMap<>();
        Map<String, String> reviewBranches = new HashMap<>();
        for (Map.Entry<String, String> e : statusShas.entrySet()) {
            String val = fetchLastNdjsonData(repository, e.getValue(), token);
            if (val != null) reviewStatuses.put(e.getKey(), val);
        }
        for (Map.Entry<String, String> e : branchShas.entrySet()) {
            String val = fetchLastNdjsonData(repository, e.getValue(), token);
            if (val != null) reviewBranches.put(e.getKey(), val);
        }

        Set<String> seenReviewIds = new HashSet<>();
        int emitted = 0;
        for (String path : sortedPaths.keySet()) {
            if (!path.startsWith(REVIEWS_PATH_PREFIX)) continue;
            String rest = path.substring(REVIEWS_PATH_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash < 0) continue;
            String reviewId   = rest.substring(0, slash);
            String streamName = rest.substring(slash + 1);
            boolean isFirst = seenReviewIds.add(reviewId);
            EventType eventType = ReviewRefParser.mapNotesEventType(streamName, isFirst);
            Map<String, String> payload = buildReviewPayload(reviewId, reviewStatuses, reviewBranches);
            ReviewEvent event = new ReviewEvent(0L, Instant.now(), repository,
                    eventType, reviewId, null, null, payload);
            try {
                sink.submit(event);
                emitted++;
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to submit review tree event for {} in {}: {}",
                        reviewId, repository, e.getMessage());
            }
        }
        LOGGER.info("Emitted {} review event(s) for {} from full tree ({} unique review(s))",
                emitted, repository, seenReviewIds.size());
        return true;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Map<String, String> buildReviewPayload(String reviewId,
                                                           Map<String, String> statuses,
                                                           Map<String, String> branches) {
        String status = statuses.get(reviewId);
        String branch = branches.get(reviewId);
        if (status == null && branch == null) return Map.of();
        Map<String, String> payload = new HashMap<>();
        if (status != null && !status.isBlank()) payload.put("status", status);
        if (branch != null && !branch.isBlank()) payload.put("branchName", branch);
        return Map.copyOf(payload);
    }

    /** Fetches a git blob and returns the {@code data} value from the last non-empty NDJSON line. */
    private String fetchLastNdjsonData(String repository, String blobSha, String token) {
        String url = getApiUrl() + "/repos/" + repository + "/git/blobs/" + blobSha;
        String body = fetchGet(url, token);
        if (body == null) return null;
        try {
            JsonObject blob = JsonParser.parseString(body).getAsJsonObject();
            String encoded = blob.get("content").getAsString().replaceAll("\\s", "");
            String text = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8).trim();
            String[] lines = text.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                JsonObject entry = JsonParser.parseString(line).getAsJsonObject();
                if (entry.has("data")) {
                    JsonElement data = entry.get("data");
                    return data.isJsonPrimitive() ? data.getAsString() : data.toString();
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to parse blob {} for {}: {}", blobSha, repository, e.getMessage());
        }
        return null;
    }

    private void LOGGERCompareStats(JsonObject compare, String repository,
                                  String fromCommit, String toCommit) {
        int aheadBy = compare.has("ahead_by") ? compare.get("ahead_by").getAsInt() : -1;
        LOGGER.info("Reconciling {} commit(s) for {} ({}...{})",
                aheadBy < 0 ? "unknown" : String.valueOf(aheadBy),
                repository, abbrev(fromCommit), abbrev(toCommit));
        if (aheadBy > COMPARE_FILE_LIMIT) {
            LOGGER.warn("{} commits in range for {} exceeds GitHub Compare limit of {} files — " +
                     "reconciliation may be partial.",
                     aheadBy, repository, COMPARE_FILE_LIMIT);
        }
    }

    private void emitEventsFromFiles(JsonObject compare, String repository,
                                      String toCommit, String token, EventSink sink) {
        if (!compare.has("files") || compare.get("files").isJsonNull()) {
            LOGGER.debug("No files in compare response for {}", repository);
            return;
        }
        Instant timestamp = extractHeadTimestamp(compare, toCommit);
        JsonArray files = compare.getAsJsonArray("files");

        // Collect blob SHAs for status/branch files changed in this range.
        Map<String, String> statusShas = new HashMap<>();
        Map<String, String> branchShas = new HashMap<>();
        for (JsonElement fileEl : files) {
            JsonObject fileObj = fileEl.getAsJsonObject();
            String filename = fileObj.get("filename").getAsString();
            if (!filename.startsWith(REVIEWS_PATH_PREFIX)) continue;
            String rest = filename.substring(REVIEWS_PATH_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash < 0) continue;
            String reviewId   = rest.substring(0, slash);
            String streamPath = rest.substring(slash + 1);
            if (fileObj.has("sha") && !fileObj.get("sha").isJsonNull()) {
                String sha = fileObj.get("sha").getAsString();
                if ("metadata/status".equals(streamPath)) statusShas.put(reviewId, sha);
                else if ("metadata/branch".equals(streamPath)) branchShas.put(reviewId, sha);
            }
        }

        Map<String, String> reviewStatuses = new HashMap<>();
        Map<String, String> reviewBranches = new HashMap<>();
        for (Map.Entry<String, String> e : statusShas.entrySet()) {
            String val = fetchLastNdjsonData(repository, e.getValue(), token);
            if (val != null) reviewStatuses.put(e.getKey(), val);
        }
        for (Map.Entry<String, String> e : branchShas.entrySet()) {
            String val = fetchLastNdjsonData(repository, e.getValue(), token);
            if (val != null) reviewBranches.put(e.getKey(), val);
        }

        Set<String> seenReviewIds = new HashSet<>();
        int emitted = 0;

        for (JsonElement fileEl : files) {
            String filename = fileEl.getAsJsonObject().get("filename").getAsString();
            if (!filename.startsWith(REVIEWS_PATH_PREFIX)) {
                continue;
            }
            String rest = filename.substring(REVIEWS_PATH_PREFIX.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String reviewId = rest.substring(0, slash);
            String streamName = rest.substring(slash + 1);
            boolean isFirst = seenReviewIds.add(reviewId);
            EventType eventType = ReviewRefParser.mapNotesEventType(streamName, isFirst);
            Map<String, String> payload = buildReviewPayload(reviewId, reviewStatuses, reviewBranches);
            ReviewEvent event = new ReviewEvent(0L, timestamp, repository, eventType,
                    reviewId, null, null, payload);
            try {
                sink.submit(event);
                emitted++;
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to submit reconciled event for review {} in {}: {}",
                        reviewId, repository, e.getMessage());
            }
        }
        LOGGER.info("Emitted {} reconciled event(s) for {} ({} unique review(s))",
                emitted, repository, seenReviewIds.size());
    }

    private Instant extractHeadTimestamp(JsonObject compare, String toCommit) {
        try {
            if (compare.has("commits")) {
                JsonArray commits = compare.getAsJsonArray("commits");
                for (int i = commits.size() - 1; i >= 0; i--) {
                    JsonObject commit = commits.get(i).getAsJsonObject();
                    if (toCommit.equals(commit.get("sha").getAsString())) {
                        String date = commit.getAsJsonObject("commit")
                                .getAsJsonObject("author")
                                .get("date").getAsString();
                        return Instant.parse(date);
                    }
                }
                // Fall back to last commit in the array
                if (!commits.isEmpty()) {
                    String date = commits.get(commits.size() - 1).getAsJsonObject()
                            .getAsJsonObject("commit")
                            .getAsJsonObject("author")
                            .get("date").getAsString();
                    return Instant.parse(date);
                }
            }
        } catch (Exception ignored) {
        }
        return Instant.now();
    }

    private String fetchGet(String url, String token) {
        try {
            HttpResponse<String> response = http.send(
                    buildRequest(url, token), HttpResponse.BodyHandlers.ofString());
            if (metrics != null) metrics.recordProviderApiCall();
            if (response.statusCode() == 401) {
                LOGGER.warn("GitHub API returned 401 Unauthorized for {} — " +
                         "verify that 'apiToken' in plugin configuration is valid and not expired", url);
                return null;
            }
            if (response.statusCode() != 200) {
                LOGGER.warn("GitHub API returned {} for {}", response.statusCode(), url);
                return null;
            }
            return response.body();
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOGGER.warn("Error fetching {}: {} — {}", url, e.getClass().getSimpleName(), e.getMessage());
            LOGGER.debug("Full exception for {}", url, e);
            return null;
        }
    }

    private HttpRequest buildRequest(String url, String token) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", GitHubConstants.BEARER_PREFIX + token.strip())
                .header("Accept", GitHubConstants.ACCEPT_HEADER)
                .header(GitHubConstants.API_VERSION_HEADER, GitHubConstants.API_VERSION)
                .GET()
                .build();
    }

    private static String abbrev(String sha) {
        return sha != null && sha.length() >= 7 ? sha.substring(0, 7) : sha;
    }
}
