package com.personal.baton.application.identity;

import com.personal.baton.domain.identity.IdentityValidationException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class VerificationTokenHash {

    private static final byte[] DOMAIN = "baton:email-verification:v1"
            .getBytes(StandardCharsets.UTF_8);

    private VerificationTokenHash() {
    }

    static String hash(String token) {
        if (token == null || token.isBlank() || token.length() > 512) {
            throw new IdentityValidationException("이메일 인증 토큰 형식이 올바르지 않습니다");
        }
        MessageDigest digest = sha256();
        append(digest, DOMAIN);
        append(digest, token.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }

    private static void append(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }
}
