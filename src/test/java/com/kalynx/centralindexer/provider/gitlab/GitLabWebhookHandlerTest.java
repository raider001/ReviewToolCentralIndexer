package com.kalynx.centralindexer.provider.gitlab;

import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GitLabWebhookHandler}.
 */
class GitLabWebhookHandlerTest {

    private static final String SECRET = "gl-secret";
    private static final String REPO = "owner/my-repo";

    private List<ReviewEvent> submitted;
    private GitLabWebhookHandler handler;

    @BeforeEach
    void setUp() {
        submitted = new ArrayList<>();
        ProviderConfig config = new ProviderConfig("gitlab", Collections.emptyList(),
                Map.of("webhookSecret", SECRET));
        EventSink sink = event -> submitted.add(event);
        handler = new GitLabWebhookHandler(config, sink);
    }

    @Test
    void handle_notesRef_emitsReviewCreated() {
        byte[] body = buildPushBody(REPO, "refs/notes/reviews/rev-gl-001/metadata/title",
                "abc123", "alice", "2026-05-19T10:00:00Z");
        handler.handle(buildHeaders("Push Hook", "uuid-gl-1"), body);

        assertEquals(1, submitted.size());
        assertEquals(EventType.REVIEW_CREATED, submitted.get(0).eventType());
        assertEquals("rev-gl-001", submitted.get(0).reviewId());
        assertEquals("alice", submitted.get(0).actorUser());
    }

    @Test
    void handle_branchRef_emitsBranchUpdated() {
        byte[] body = buildPushBody(REPO, "refs/heads/develop",
                "deadbeef", "bob", "2026-05-19T10:00:00Z");
        handler.handle(buildHeaders("Push Hook", "uuid-gl-2"), body);

        assertEquals(1, submitted.size());
        ReviewEvent event = submitted.get(0);
        assertEquals(EventType.BRANCH_UPDATED, event.eventType());
        assertEquals("develop", event.payload().get("branch_name"));
        assertEquals("deadbeef", event.payload().get("head_commit"));
        assertNotNull(event.payload().get("repository_url"));
    }

    @Test
    void handle_branchDeleted_emitsBranchDeleted() {
        byte[] body = buildPushBody(REPO, "refs/heads/old-feature",
                "0000000000000000000000000000000000000000", "bob", "2026-05-19T10:00:00Z");
        handler.handle(buildHeaders("Push Hook", "uuid-gl-3"), body);

        assertEquals(1, submitted.size());
        assertEquals(EventType.BRANCH_DELETED, submitted.get(0).eventType());
    }

    @Test
    void handle_wrongToken_discardsEvent() {
        byte[] body = buildPushBody(REPO, "refs/notes/reviews/rev-x/metadata/title",
                "abc", "alice", "2026-05-19T10:00:00Z");
        Map<String, String> headers = Map.of(
                "x-gitlab-event", "Push Hook",
                "x-gitlab-event-uuid", "uuid-bad",
                "x-gitlab-token", "wrong-secret");
        handler.handle(headers, body);
        assertTrue(submitted.isEmpty(), "Wrong token must discard the event");
    }

    @Test
    void handle_nonPushEvent_discardsEvent() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        handler.handle(Map.of("x-gitlab-event", "Merge Request Hook"), body);
        assertTrue(submitted.isEmpty());
    }

    @Test
    void handle_unrecognisedRef_discardsEvent() {
        byte[] body = buildPushBody(REPO, "refs/tags/v2.0",
                "abc123", "alice", "2026-05-19T10:00:00Z");
        handler.handle(buildHeaders("Push Hook", "uuid-tag"), body);
        assertTrue(submitted.isEmpty());
    }

    private Map<String, String> buildHeaders(String event, String uuid) {
        return Map.of(
                "x-gitlab-event", event,
                "x-gitlab-event-uuid", uuid,
                "x-gitlab-token", SECRET);
    }

    private byte[] buildPushBody(String repo, String ref, String afterHash,
                                  String user, String timestamp) {
        return ("{\"ref\":\"" + ref + "\","
                + "\"after\":\"" + afterHash + "\","
                + "\"repository\":{\"name\":\"" + repo + "\"},"
                + "\"user_username\":\"" + user + "\","
                + "\"commits\":[{\"id\":\"" + afterHash + "\",\"timestamp\":\"" + timestamp + "\"}]}"
        ).getBytes(StandardCharsets.UTF_8);
    }
}

