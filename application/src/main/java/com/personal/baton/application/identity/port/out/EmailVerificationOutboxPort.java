package com.personal.baton.application.identity.port.out;

import com.personal.baton.application.identity.EmailVerificationOutboxDelivery;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectedPayload;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector.ProtectionContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface EmailVerificationOutboxPort {

    void enqueueReplacingPending(
            ProtectionContext context,
            ProtectedPayload protectedPayload,
            Instant enqueuedAt
    );

    int supersedePending(UUID identityId, Instant supersededAt);

    int expireUndeliverable(Instant expiredAt);

    List<EmailVerificationOutboxDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration
    );

    boolean isClaimCurrent(long deliveryId, UUID leaseToken, Instant checkedAt);

    boolean markDelivered(long deliveryId, UUID leaseToken, Instant deliveredAt);

    boolean markRetry(
            long deliveryId,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    );

    boolean markFailed(
            long deliveryId,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    );
}
