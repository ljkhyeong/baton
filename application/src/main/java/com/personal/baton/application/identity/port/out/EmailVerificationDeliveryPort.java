package com.personal.baton.application.identity.port.out;

import com.personal.baton.domain.identity.EmailChallengePurpose;
import java.time.Instant;
import java.util.UUID;

public interface EmailVerificationDeliveryPort {

    void deliver(EmailVerificationDelivery delivery);

    record EmailVerificationDelivery(
            long deliveryId,
            UUID accountId,
            String email,
            String verificationToken,
            Instant expiresAt,
            EmailChallengePurpose purpose
    ) {

        @Override
        public String toString() {
            return "EmailVerificationDelivery[accountId=" + accountId
                    + ", email=[REDACTED], verificationToken=[REDACTED], expiresAt="
                    + expiresAt + "]";
        }
    }
}
