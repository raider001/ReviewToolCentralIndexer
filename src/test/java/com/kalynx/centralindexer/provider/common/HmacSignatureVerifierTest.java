package com.kalynx.centralindexer.provider.common;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HmacSignatureVerifier}.
 */
class HmacSignatureVerifierTest {

    private static final String SECRET = "my-webhook-secret";
    private static final byte[] BODY = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void verify_validSignature_returnsTrue() throws Exception {
        String signature = "sha256=" + computeHmac(SECRET, BODY);
        assertTrue(HmacSignatureVerifier.verify(SECRET, BODY, signature));
    }

    @Test
    void verify_invalidHexSignature_returnsFalse() {
        assertFalse(HmacSignatureVerifier.verify(SECRET, BODY, "sha256=deadbeef"));
    }

    @Test
    void verify_wrongSecret_returnsFalse() throws Exception {
        String signature = "sha256=" + computeHmac("wrong-secret", BODY);
        assertFalse(HmacSignatureVerifier.verify(SECRET, BODY, signature));
    }

    @Test
    void verify_signatureWithoutSha256Prefix_returnsFalse() throws Exception {
        String signature = computeHmac(SECRET, BODY);
        assertFalse(HmacSignatureVerifier.verify(SECRET, BODY, signature));
    }

    @Test
    void verify_nullSignature_returnsFalse() {
        assertFalse(HmacSignatureVerifier.verify(SECRET, BODY, null));
    }

    @Test
    void verify_nullSecret_returnsFalse() {
        assertFalse(HmacSignatureVerifier.verify(null, BODY, "sha256=anything"));
    }

    @Test
    void verify_blankSecret_returnsFalse() {
        assertFalse(HmacSignatureVerifier.verify("  ", BODY, "sha256=anything"));
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

