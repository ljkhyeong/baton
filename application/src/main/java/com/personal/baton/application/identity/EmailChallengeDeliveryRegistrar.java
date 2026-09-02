package com.personal.baton.application.identity;

import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.PlainPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class EmailChallengeDeliveryRegistrar {

    private final EmailVerificationOutboxPort outboxPort;
    private final EmailVerificationOutboxPayloadProtector payloadProtector;

    EmailChallengeDeliveryRegistrar(
            EmailVerificationOutboxPort outboxPort,
            EmailVerificationOutboxPayloadProtector payloadProtector
    ) {
        this.outboxPort = outboxPort;
        this.payloadProtector = payloadProtector;
    }

    void enqueue(
            UUID identityId,
            String challengeTokenHash,
            UUID accountId,
            String email,
            String verificationToken,
            Instant expiresAt,
            Instant enqueuedAt
    ) {
        ProtectionContext context = new ProtectionContext(
                identityId,
                accountId,
                challengeTokenHash,
                expiresAt
        );
        var protectedPayload = payloadProtector.protect(
                context,
                new PlainPayload(email, verificationToken)
        );
        outboxPort.enqueueReplacingPending(context, protectedPayload, enqueuedAt);
    }
}
