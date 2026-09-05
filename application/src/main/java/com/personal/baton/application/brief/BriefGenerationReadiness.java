package com.personal.baton.application.brief;

import java.time.Instant;

public record BriefGenerationReadiness(Status status, long pendingCount, long failedCount,
                                       Instant lastDeliveredAt, Instant checkedAt) {
    public enum Status { READY, DELIVERY_PENDING, DELIVERY_FAILED, GENERATING, GENERATION_FAILED, SEASON_ENDED, DISABLED }
}
