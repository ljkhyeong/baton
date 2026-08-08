package com.personal.baton.application.identity.port.out;

import java.time.Instant;
import java.util.UUID;

public interface EmailVerificationDeliveryPort {

    void deliver(EmailVerificationDelivery delivery);

    record EmailVerificationDelivery(
            UUID accountId,
            String email,
            String verificationToken,
            Instant expiresAt
    ) {

        @Override
        public String toString() {
            return "EmailVerificationDelivery[accountId=" + accountId
                    + ", email=[REDACTED], verificationToken=[REDACTED], expiresAt="
                    + expiresAt + "]";
        }
    }
}
