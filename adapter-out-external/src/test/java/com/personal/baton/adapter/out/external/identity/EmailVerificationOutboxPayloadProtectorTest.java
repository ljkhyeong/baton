package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.error.EmailVerificationPayloadProtectionException;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.PlainPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectedPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailVerificationOutboxPayloadProtectorTest {

    private static final String TEST_KEY =
            "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";
    private static final PlainPayload PLAIN_PAYLOAD = new PlainPayload(
            "study.user@example.com",
            "secure-email-verification-token-000000000001"
    );

    @DisplayName("AES-256-GCM protector는 이메일과 토큰을 노출하지 않는 payload로 왕복한다")
    @Test
    void protectsAndUnprotectsPayload() {
        var protector = new AesGcmEmailVerificationOutboxPayloadProtector(TEST_KEY);
        ProtectionContext context = context();

        ProtectedPayload protectedPayload = protector.protect(context, PLAIN_PAYLOAD);
        PlainPayload restored = protector.unprotect(context, protectedPayload);

        assertThat(restored).isEqualTo(PLAIN_PAYLOAD);
        assertThat(protectedPayload.ciphertext())
                .doesNotContain(PLAIN_PAYLOAD.email(), PLAIN_PAYLOAD.verificationToken());
        assertThat(protectedPayload.nonce()).hasSize(16);
        assertThat(protectedPayload.toString())
                .doesNotContain(protectedPayload.ciphertext(), protectedPayload.nonce());
    }

    @DisplayName("암호문·nonce·계정 정보가 변조되면 복호화를 거부한다")
    @Test
    void rejectsCiphertextAndContextTampering() {
        var protector = new AesGcmEmailVerificationOutboxPayloadProtector(TEST_KEY);
        ProtectionContext context = context();
        ProtectedPayload protectedPayload = protector.protect(context, PLAIN_PAYLOAD);
        String ciphertext = protectedPayload.ciphertext();
        char replacement = ciphertext.endsWith("A") ? 'B' : 'A';
        ProtectedPayload tampered = new ProtectedPayload(
                ciphertext.substring(0, ciphertext.length() - 1) + replacement,
                protectedPayload.nonce()
        );
        String nonce = protectedPayload.nonce();
        ProtectedPayload tamperedNonce = new ProtectedPayload(
                ciphertext, (nonce.startsWith("A") ? "B" : "A") + nonce.substring(1)
        );
        ProtectionContext wrongAccount = new ProtectionContext(
                context.identityId(),
                UUID.randomUUID(),
                context.challengeTokenHash(),
                context.expiresAt()
        );

        assertThatThrownBy(() -> protector.unprotect(context, tampered))
                .isInstanceOf(EmailVerificationPayloadProtectionException.class)
                .extracting(exception -> ((EmailVerificationPayloadProtectionException) exception)
                        .isRetryable())
                .isEqualTo(false);
        assertThatThrownBy(() -> protector.unprotect(wrongAccount, protectedPayload))
                .isInstanceOf(EmailVerificationPayloadProtectionException.class);
        assertThatThrownBy(() -> protector.unprotect(context, tamperedNonce))
                .isInstanceOf(EmailVerificationPayloadProtectionException.class);
    }

    @DisplayName("Base64 키는 정확히 32바이트가 아니면 protector 구성을 거부한다")
    @Test
    void requiresAes256Key() {
        assertThatThrownBy(() -> new AesGcmEmailVerificationOutboxPayloadProtector("not-base64"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AesGcmEmailVerificationOutboxPayloadProtector("AQEBAQ=="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32바이트");
    }

    @DisplayName("키가 설정되지 않은 protector는 평문 저장으로 fallback하지 않는다")
    @Test
    void missingKeyFailsClosed() {
        var protector = new DisabledEmailVerificationOutboxPayloadProtector();

        assertThatThrownBy(() -> protector.protect(context(), PLAIN_PAYLOAD))
                .isInstanceOf(EmailVerificationPayloadProtectionException.class)
                .extracting(exception -> ((EmailVerificationPayloadProtectionException) exception)
                        .isRetryable())
                .isEqualTo(true);
    }

    @DisplayName("identity 메일 설정 문자열은 outbox 암호화 키를 노출하지 않는다")
    @Test
    void redactsEncryptionKeyFromPropertiesString() {
        var properties = new IdentityEmailVerificationProperties(
                IdentityEmailVerificationProperties.Delivery.SMTP,
                "https://manager.b4ton.com",
                "no-reply@b4ton.com",
                TEST_KEY
        );

        assertThat(properties.toString())
                .contains("outboxEncryptionKey=<redacted>")
                .doesNotContain(TEST_KEY);
    }

    private ProtectionContext context() {
        return new ProtectionContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "a".repeat(64),
                Instant.parse("2026-08-08T01:32:03.123456789Z")
        );
    }
}
