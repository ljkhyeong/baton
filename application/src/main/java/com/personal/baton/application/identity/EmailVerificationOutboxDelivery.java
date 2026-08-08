package com.personal.baton.application.identity;

import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectedPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
import java.util.Objects;
import java.util.UUID;

public record EmailVerificationOutboxDelivery(
        long deliveryId,
        ProtectionContext protectionContext,
        ProtectedPayload protectedPayload,
        int attemptCount,
        UUID leaseToken
) {

    public EmailVerificationOutboxDelivery {
        if (deliveryId < 1) {
            throw new IllegalArgumentException("이메일 인증 outbox ID는 1 이상이어야 합니다");
        }
        Objects.requireNonNull(protectionContext, "이메일 인증 payload context는 필수입니다");
        Objects.requireNonNull(protectedPayload, "이메일 인증 보호 payload는 필수입니다");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("이메일 인증 전달 시도 횟수는 1 이상이어야 합니다");
        }
        Objects.requireNonNull(leaseToken, "이메일 인증 lease token은 필수입니다");
    }

    @Override
    public String toString() {
        return "EmailVerificationOutboxDelivery[deliveryId=" + deliveryId
                + ", protectionContext=" + protectionContext
                + ", protectedPayload=[REDACTED], attemptCount=" + attemptCount
                + ", leaseToken=" + leaseToken + "]";
    }
}
