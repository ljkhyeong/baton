package com.personal.baton.application.identity.port.out;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public interface EmailVerificationOutboxPayloadProtector {

    ProtectedPayload protect(ProtectionContext context, PlainPayload payload);

    PlainPayload unprotect(ProtectionContext context, ProtectedPayload payload);

    record ProtectionContext(
            UUID identityId,
            UUID accountId,
            String challengeTokenHash,
            Instant expiresAt
    ) {

        private static final Pattern TOKEN_HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

        public ProtectionContext {
            Objects.requireNonNull(identityId, "이메일 인증 identity ID는 필수입니다");
            Objects.requireNonNull(accountId, "이메일 인증 Account ID는 필수입니다");
            expiresAt = Objects.requireNonNull(
                    expiresAt,
                    "이메일 인증 만료 시각은 필수입니다"
            ).truncatedTo(ChronoUnit.MICROS);
            if (challengeTokenHash == null
                    || !TOKEN_HASH_PATTERN.matcher(challengeTokenHash).matches()) {
                throw new IllegalArgumentException("이메일 인증 challenge hash가 올바르지 않습니다");
            }
        }
    }

    record PlainPayload(String email, String verificationToken) {

        public PlainPayload {
            if (email == null || email.isBlank() || email.length() > 320) {
                throw new IllegalArgumentException("유효한 이메일 인증 수신 주소가 필요합니다");
            }
            if (verificationToken == null
                    || !verificationToken.matches("[A-Za-z0-9_-]{32,512}")) {
                throw new IllegalArgumentException("안전한 이메일 인증 토큰이 필요합니다");
            }
        }

        @Override
        public String toString() {
            return "PlainPayload[email=[REDACTED], verificationToken=[REDACTED]]";
        }
    }

    record ProtectedPayload(String ciphertext, String nonce) {

        public ProtectedPayload {
            if (ciphertext == null || ciphertext.isBlank() || ciphertext.length() > 4096) {
                throw new IllegalArgumentException("이메일 인증 ciphertext가 올바르지 않습니다");
            }
            if (nonce == null || nonce.isBlank() || nonce.length() > 32) {
                throw new IllegalArgumentException("이메일 인증 nonce가 올바르지 않습니다");
            }
        }

        @Override
        public String toString() {
            return "ProtectedPayload[ciphertext=[REDACTED], nonce=[REDACTED]]";
        }
    }
}
