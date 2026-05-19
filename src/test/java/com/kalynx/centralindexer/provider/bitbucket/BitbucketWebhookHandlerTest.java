package com.kalynx.centralindexer.provider.bitbucket;

import com.kalynx.centralindexer.model.EventType;
import com.kalynx.centralindexer.model.ReviewEvent;
import com.kalynx.centralindexer.spi.EventSink;
import com.kalynx.centralindexer.spi.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BitbucketWebhookHandler}.
 */
class BitbucketWebhookHandlerTest {

    private static final String SECRET = "bb-secret";

    private List<ReviewEvent> submitted;
    private BitbucketWebhookHandler handler;

    @BeforeEach
    void setUp() {
        submitted = new ArrayList<>();
        ProviderConfig config = new ProviderConfig("bitbucket", Collections.emptyList(),
                Map.of("webhookSecret", SECRET, "allowUnsigned", "false"));
        EventSink sink = event -> submitted.add(event);
        handler = new BitbucketWebhookHandler(config, sink);
    }

    @Test
    void cloudNotesRefEmitsReviewCreated() throws Exception {
        String repo = "owner/my-repo";
        byte[] body = buildCloudPushBody(repo,
                "refs/notes/reviews/rev-bb-001/metadata/title", false);
        handler.handle(buildHeaders(body, "repo:push", "uuid-1"), body);

        assertEquals(1, submitted.size());
        assertEquals(EventType.REVIEW_CREATED, submitted.get(0).eventType());
        assertEquals("rev-bb-001", submitted.get(0).reviewId());
    }

    @Test
    void cloudBranchRefEmitsBranchUpdated() throws Exception {
        byte[] body = buildCloudPushBody("owner/my-repo", "refs/heads/main", false);
        handler.handle(buildHeaders(body, "repo:push", "uuid-2"), body);

        assertEquals(1, submitted.size());
        assertEquals(EventType.BRANCH_UPDATED, submitted.get(0).eventType());
    }

    @Test
    void cloudBranchDeletedEmitsBranchDeleted() throws Exception {
        byte[] body = buildCloudPushBodyDeleted("owner/my-repo", "refs/heads/old-branch");
        handler.handle(buildHeaders(body, "repo:push", "uuid-3"), body);

        assertEquals(1, submitted.size());
        assertEquals(EventType.BRANCH_DELETED, submitted.get(0).eventType());
    }

    @Test
    void dataCenterNotesRefEmitsReviewUpdated() throws Exception {
        byte[] body = buildDataCenterPushBody("my-repo",
                "refs/notes/reviews/rev-dc-001/metadata/status", false);
        handler.handle(buildHeaders(body, "repo:refs_changed", "uuid-dc-1"), body);

        assertEquals(1, submitted.size());
        assertEquals(EventType.REVIEW_UPDATED, submitted.get(0).eventType());
    }

    @Test
    void invalidSignatureDiscards() throws Exception {
        byte[] body = buildCloudPushBody("owner/repo",
                "refs/notes/reviews/rev-x/metadata/title", false);
        Map<String, String> headers = Map.of(
                "x-event-key", "repo:push",
                "x-request-uuid", "uuid-bad",
                "x-hub-signature", "sha256=badsignature");
        handler.handle(headers, body);
        assertTrue(submitted.isEmpty());
    }

    @Test
    void nonPushEventDiscards() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        handler.handle(Map.of("x-event-key", "pr:opened"), body);
        assertTrue(submitted.isEmpty());
    }

    private Map<String, String> buildHeaders(byte[] body, String eventKey, String uuid)
            throws Exception {
        String sig = "sha256=" + computeHmac(SECRET, body);
        return Map.of(
                "x-event-key", eventKey,
                "x-request-uuid", uuid,
                "x-hub-signature", sig);
    }

    private byte[] buildCloudPushBody(String repo, String refName, boolean isDelete) {
        String newPart = isDelete ? "null"
                : "{\"name\":\"" + refName + "\",\"type\":\"branch\","
                + "\"target\":{\"hash\":\"abc123\"}}";
        String oldPart = "{\"name\":\"" + refName + "\"}";
        return ("{\"repository\":{\"full_name\":\"" + repo + "\",\"name\":\"my-repo\"},"
                + "\"push\":{\"changes\":[{\"new\":" + newPart + ",\"old\":" + oldPart + ","
                + "\"commits\":[{\"hash\":\"abc123\",\"date\":\"2026-05-19T10:00:00Z\","
                + "\"author\":{\"user\":{\"nickname\":\"alice\"}}}]}]}}"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildCloudPushBodyDeleted(String repo, String refName) {
        return ("{\"repository\":{\"full_name\":\"" + repo + "\",\"name\":\"my-repo\"},"
                + "\"push\":{\"changes\":[{\"new\":null,"
                + "\"old\":{\"name\":\"" + refName + "\"},"
                + "\"commits\":[]}]}}"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] buildDataCenterPushBody(String repo, String refId, boolean isDelete) {
        String type = isDelete ? "DELETE" : "UPDATE";
        return ("{\"repository\":{\"slug\":\"" + repo + "\"},"
                + "\"actor\":{\"name\":\"bob\"},"
                + "\"refChanges\":[{\"refId\":\"" + refId + "\","
                + "\"toHash\":\"abc123\",\"type\":\"" + type + "\"}]}"
        ).getBytes(StandardCharsets.UTF_8);
    }

    private String computeHmac(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}

