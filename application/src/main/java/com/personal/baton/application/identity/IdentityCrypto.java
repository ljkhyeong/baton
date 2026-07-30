package com.personal.baton.application.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class IdentityCrypto {

    private IdentityCrypto() {
    }

    static String sha256Hex(String domain, String value) {
        return sha256Hex(domain, List.of(value));
    }

    static String sha256Hex(String domain, List<String> values) {
        MessageDigest digest = sha256Digest();
        update(digest, domain);
        for (String value : values) {
            update(digest, value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256HexOfToken(String token) {
        return HexFormat.of().formatHex(
                sha256Digest().digest(token.getBytes(StandardCharsets.UTF_8))
        );
    }

    static byte[] hmacSha256(String secret, String domain, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            update(mac, domain);
            update(mac, value);
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("HMAC-SHA-256을 사용할 수 없습니다", exception);
        }
    }

    static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void update(Mac mac, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        mac.update(bytes);
    }
}
