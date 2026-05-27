package com.kalynx.centralindexer.comments;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a list of changed file paths from a {@code kalynx-reviews} commit diff
 * and classifies each changed comment thread as {@link CommentEventType#ADDED} or
 * {@link CommentEventType#UPDATED}.
 *
 * <p>Comment threads are stored at
 * {@code reviews/{reviewId}/comments/{commentId}/(metadata|text|status)}.
 * The sub-stream that changed determines the event type:
 * <ul>
 *   <li>{@code metadata} or {@code text} changed → {@code ADDED}
 *       (new comment created or reply appended)</li>
 *   <li>Only {@code status} changed → {@code UPDATED}
 *       (resolution state changed)</li>
 * </ul>
 * If the same {@code commentId} has both add-triggering and status-only changes in one
 * commit, {@code ADDED} takes precedence.
 */
public final class CommentChangeDetector {

    private static final Pattern PATH_PATTERN =
            Pattern.compile("^reviews/([^/]+)/comments/([^/]+)/(metadata|text|status)$");

    private CommentChangeDetector() {}

    /**
     * Detects comment changes in a list of changed file paths.
     *
     * @param changedPaths list of file paths changed in a commit; non-null
     * @return one {@link CommentChange} per distinct {@code commentId}; order matches
     *         first occurrence in input (by reviewId then commentId)
     */
    public static List<CommentChange> detect(List<String> changedPaths) {
        // reviewId → commentId → set of sub-streams seen
        Map<String, Map<String, Set<String>>> groups = new LinkedHashMap<>();
        for (String path : changedPaths) {
            Matcher m = PATH_PATTERN.matcher(path);
            if (!m.matches()) continue;
            groups.computeIfAbsent(m.group(1), k -> new LinkedHashMap<>())
                  .computeIfAbsent(m.group(2), k -> new HashSet<>())
                  .add(m.group(3));
        }

        List<CommentChange> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Set<String>>> reviewEntry : groups.entrySet()) {
            for (Map.Entry<String, Set<String>> commentEntry : reviewEntry.getValue().entrySet()) {
                Set<String> streams = commentEntry.getValue();
                CommentEventType type = (streams.contains("metadata") || streams.contains("text"))
                        ? CommentEventType.ADDED
                        : CommentEventType.UPDATED;
                result.add(new CommentChange(reviewEntry.getKey(), commentEntry.getKey(), type));
            }
        }
        return result;
    }
}
