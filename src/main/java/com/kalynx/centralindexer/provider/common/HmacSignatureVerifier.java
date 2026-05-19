package com.kalynx.centralindexer.provider.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Verifies HMAC-SHA256 webhook signatures used by GitHub and Bitbucket.
 *
 * <p>The expected header format is {@code sha256=<hex-digest>}.
 * All comparisons use {@link MessageDigest#isEqual} to prevent timing attacks.
 */
public final class HmacSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private HmacSignatureVerifier() {
    }

    /**
     * Verifies that the signature header matches HMAC-SHA256 of the raw body.
     *
     * @param secret          the webhook secret
     * @param rawBody         the raw request body bytes
     * @param signatureHeader the full header value (e.g. {@code "sha256=abc123..."})
     * @return {@code true} if the signature is valid
     */
    public static boolean verify(String secret, byte[] rawBody, String signatureHeader) {
        if (secret == null || secret.isBlank() || signatureHeader == null) {
            return false;
        }
        if (!signatureHeader.startsWith(PREFIX)) {
            return false;
        }
        String expectedHex = signatureHeader.substring(PREFIX.length());
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] computed = mac.doFinal(rawBody);
            byte[] expected = hexToBytes(expectedHex);
            return MessageDigest.isEqual(computed, expected);
        } catch (NoSuchAlgorithmException | InvalidKeyException | IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("Odd hex length");
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex character");
            }
            result[i] = (byte) ((hi << 4) | lo);
        }
        return result;
    }
}

