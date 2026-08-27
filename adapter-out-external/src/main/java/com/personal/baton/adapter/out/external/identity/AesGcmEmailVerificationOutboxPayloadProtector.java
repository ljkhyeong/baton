package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.error.EmailVerificationPayloadProtectionException;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.PlainPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectedPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmEmailVerificationOutboxPayloadProtector
        implements EmailVerificationOutboxPayloadProtector {

    private static final byte[] AAD_DOMAIN = "baton:email-verification-outbox:v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final int AES_256_KEY_BYTES = 32;
    private static final int GCM_NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAXIMUM_PLAINTEXT_BYTES = 2048;
    private static final int MAXIMUM_CIPHERTEXT_BYTES = MAXIMUM_PLAINTEXT_BYTES + 32;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom;

    public AesGcmEmailVerificationOutboxPayloadProtector(String base64Key) {
        this(base64Key, new SecureRandom());
    }

    AesGcmEmailVerificationOutboxPayloadProtector(
            String base64Key,
            SecureRandom secureRandom
    ) {
        this.key = requiredSecretKey(base64Key);
        this.secureRandom = Objects.requireNonNull(secureRandom, "보안 난수 생성기는 필수입니다");
    }

    @Override
    public ProtectedPayload protect(ProtectionContext context, PlainPayload payload) {
        Objects.requireNonNull(context, "이메일 인증 payload context는 필수입니다");
        Objects.requireNonNull(payload, "이메일 인증 평문 payload는 필수입니다");
        byte[] nonce = new byte[GCM_NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        byte[] plaintext = serialize(payload);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new ProtectedPayload(
                    Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
            );
        } catch (GeneralSecurityException exception) {
            throw new EmailVerificationPayloadProtectionException(
                    "이메일 인증 payload를 암호화하지 못했습니다",
                    false,
                    exception
            );
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    @Override
    public PlainPayload unprotect(ProtectionContext context, ProtectedPayload payload) {
        Objects.requireNonNull(context, "이메일 인증 payload context는 필수입니다");
        Objects.requireNonNull(payload, "이메일 인증 보호 payload는 필수입니다");
        byte[] nonce;
        byte[] ciphertext;
        try {
            nonce = Base64.getUrlDecoder().decode(payload.nonce());
            ciphertext = Base64.getUrlDecoder().decode(payload.ciphertext());
        } catch (IllegalArgumentException exception) {
            throw invalidPayload(exception);
        }
        if (!isCanonicalBase64Url(payload.nonce(), nonce)
                || !isCanonicalBase64Url(payload.ciphertext(), ciphertext)
                || nonce.length != GCM_NONCE_BYTES
                || ciphertext.length < 16
                || ciphertext.length > MAXIMUM_CIPHERTEXT_BYTES) {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            throw invalidPayload(null);
        }

        byte[] plaintext = null;
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, nonce, context);
            plaintext = cipher.doFinal(ciphertext);
            return deserialize(plaintext);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw invalidPayload(exception);
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
            if (plaintext != null) {
                Arrays.fill(plaintext, (byte) 0);
            }
        }
    }

    private Cipher cipher(int mode, byte[] nonce, ProtectionContext context)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad(context));
        return cipher;
    }

    private byte[] serialize(PlainPayload payload) {
        byte[] email = payload.email().getBytes(StandardCharsets.UTF_8);
        byte[] token = payload.verificationToken().getBytes(StandardCharsets.UTF_8);
        if (Integer.BYTES * 2L + email.length + token.length > MAXIMUM_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("이메일 인증 payload가 허용 크기를 초과합니다");
        }
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 2 + email.length + token.length);
        buffer.putInt(email.length).put(email).putInt(token.length).put(token);
        Arrays.fill(email, (byte) 0);
        Arrays.fill(token, (byte) 0);
        return buffer.array();
    }

    private PlainPayload deserialize(byte[] plaintext) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(plaintext);
            String email = readUtf8(buffer, 1, 1280);
            String token = readUtf8(buffer, 32, 512);
            if (buffer.hasRemaining()) {
                throw new IllegalArgumentException("이메일 인증 payload 뒤에 예상하지 못한 값이 있습니다");
            }
            return new PlainPayload(email, token);
        } catch (BufferUnderflowException | IllegalArgumentException exception) {
            throw invalidPayload(exception);
        }
    }

    private String readUtf8(ByteBuffer buffer, int minimumBytes, int maximumBytes) {
        int length = buffer.getInt();
        if (length < minimumBytes || length > maximumBytes || length > buffer.remaining()) {
            throw new IllegalArgumentException("이메일 인증 payload 길이가 올바르지 않습니다");
        }
        byte[] value = new byte[length];
        buffer.get(value);
        String decoded = new String(value, StandardCharsets.UTF_8);
        Arrays.fill(value, (byte) 0);
        return decoded;
    }

    private byte[] aad(ProtectionContext context) {
        byte[] challengeHash = context.challengeTokenHash().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(
                Integer.BYTES * 3
                        + Long.BYTES * 5
                        + AAD_DOMAIN.length
                        + challengeHash.length
        );
        buffer.putInt(AAD_DOMAIN.length)
                .put(AAD_DOMAIN)
                .putLong(context.identityId().getMostSignificantBits())
                .putLong(context.identityId().getLeastSignificantBits())
                .putLong(context.accountId().getMostSignificantBits())
                .putLong(context.accountId().getLeastSignificantBits())
                .putInt(challengeHash.length)
                .put(challengeHash)
                .putLong(context.expiresAt().getEpochSecond())
                .putInt(context.expiresAt().getNano());
        return buffer.array();
    }

    private EmailVerificationPayloadProtectionException invalidPayload(Throwable cause) {
        return new EmailVerificationPayloadProtectionException(
                "이메일 인증 보호 payload가 올바르지 않습니다",
                false,
                cause
        );
    }

    private boolean isCanonicalBase64Url(String encoded, byte[] decoded) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded);
    }

    private static byte[] requiredKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("이메일 인증 outbox 암호화 키는 필수입니다");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "이메일 인증 outbox 암호화 키는 Base64여야 합니다",
                    exception
            );
        }
        if (decoded.length != AES_256_KEY_BYTES) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalArgumentException(
                    "이메일 인증 outbox 암호화 키는 32바이트여야 합니다"
            );
        }
        return decoded;
    }

    private static SecretKeySpec requiredSecretKey(String base64Key) {
        byte[] decoded = requiredKey(base64Key);
        try {
            return new SecretKeySpec(decoded, "AES");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }
}
