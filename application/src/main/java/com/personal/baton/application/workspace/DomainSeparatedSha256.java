package com.personal.baton.application.workspace;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class DomainSeparatedSha256 {

    private static final String ALGORITHM = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    private final MessageDigest digest;

    private DomainSeparatedSha256(String domain) {
        this.digest = newDigest();
        append(domain);
    }

    static DomainSeparatedSha256 inDomain(String domain) {
        return new DomainSeparatedSha256(domain);
    }

    static byte[] hash(String domain, List<String> values) {
        DomainSeparatedSha256 fingerprint = inDomain(domain);
        for (String value : values) {
            fingerprint.append(value);
        }
        return fingerprint.digest();
    }

    static String hashHex(String domain, List<String> values) {
        return HEX.formatHex(hash(domain, values));
    }

    static byte[] hashUtf8(String value) {
        return newDigest().digest(value.getBytes(StandardCharsets.UTF_8));
    }

    static String hashUtf8Hex(String value) {
        return HEX.formatHex(hashUtf8(value));
    }

    DomainSeparatedSha256 append(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
        return this;
    }

    DomainSeparatedSha256 appendNullable(Object value) {
        if (value == null) {
            digest.update((byte) 0);
            return this;
        }
        digest.update((byte) 1);
        return append(value.toString());
    }

    byte[] digest() {
        return digest.digest();
    }

    String digestHex() {
        return HEX.formatHex(digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }
}
