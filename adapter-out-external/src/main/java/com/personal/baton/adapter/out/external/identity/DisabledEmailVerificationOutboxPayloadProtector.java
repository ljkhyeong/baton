package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.error.EmailVerificationPayloadProtectionException;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector;

final class DisabledEmailVerificationOutboxPayloadProtector
        implements EmailVerificationOutboxPayloadProtector {

    @Override
    public ProtectedPayload protect(ProtectionContext context, PlainPayload payload) {
        throw unavailable();
    }

    @Override
    public PlainPayload unprotect(ProtectionContext context, ProtectedPayload payload) {
        throw unavailable();
    }

    private EmailVerificationPayloadProtectionException unavailable() {
        return new EmailVerificationPayloadProtectionException(
                "이메일 인증 outbox 암호화 키가 설정되지 않았습니다",
                true
        );
    }
}
