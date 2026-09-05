package com.personal.baton.application.brief.port.out;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.Optional;

public interface BriefEditionGenerationExecutionPort {

    record DeliveryBoundary(long watermark, long pendingCount, long failedCount, Instant lastDeliveredAt) {
        public boolean complete() { return pendingCount == 0 && failedCount == 0; }
    }

    record ExecutionState(String status, Instant leaseExpiresAt) { }

    Optional<ExecutionState> findExecutionState(GenerationTarget target);

    record GenerationTarget(
            UUID teamId,
            UUID seasonId,
            LocalDate weekStart,
            ZoneId zoneId,
            long deliveryWatermark
    ) {
    }

    sealed interface ClaimResult {

        UUID executionId();

        record Claimed(UUID executionId, UUID leaseToken) implements ClaimResult {
        }

        record DeliveryIncomplete(UUID executionId) implements ClaimResult {
        }

        record InProgress(UUID executionId) implements ClaimResult {
        }

        record Completed(
                UUID executionId,
                UUID editionId,
                long generation,
                long sourceCursor,
                String etag,
                boolean created
        ) implements ClaimResult {
        }

        record PermanentlyFailed(UUID executionId, String code) implements ClaimResult {
        }
    }

    DeliveryBoundary findDeliveryBoundary(UUID teamId, UUID seasonId);

    ClaimResult claim(
            GenerationTarget target,
            boolean deliveryComplete,
            Instant claimedAt,
            Duration leaseDuration
    );

    boolean markSucceeded(
            UUID executionId,
            UUID leaseToken,
            Instant completedAt,
            UUID editionId,
            long generation,
            long sourceCursor,
            String etag,
            boolean created
    );

    boolean markRetryableFailure(
            UUID executionId,
            UUID leaseToken,
            Instant failedAt,
            String code
    );

    boolean markPermanentFailure(
            UUID executionId,
            UUID leaseToken,
            Instant failedAt,
            String code
    );
}

