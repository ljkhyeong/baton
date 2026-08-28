package com.personal.baton.application.brief.port.out;

import com.personal.baton.application.brief.BriefContinuityDelivery;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BriefContinuityOutboxPort {

    List<BriefContinuityDelivery> claimPending(
            int batchSize,
            Instant claimedAt,
            Duration leaseDuration
    );

    boolean markDelivered(
            long outboxId,
            UUID leaseToken,
            Instant deliveredAt,
            String resultCode
    );

    boolean markRetry(
            long outboxId,
            UUID leaseToken,
            Instant availableAt,
            String errorCode
    );

    boolean markFailed(
            long outboxId,
            UUID leaseToken,
            Instant failedAt,
            String errorCode
    );
}
