package com.personal.baton.application.brief;

import java.util.Objects;
import java.util.UUID;

public record BriefContinuityDelivery(
        long outboxId,
        BriefContinuityEvent event,
        int attemptCount,
        UUID leaseToken
) {

    public BriefContinuityDelivery {
        if (outboxId < 1) {
            throw new IllegalArgumentException("BRIEF outbox ID는 양수여야 합니다");
        }
        Objects.requireNonNull(event, "BRIEF 전달 이벤트는 필수입니다");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("BRIEF 전달 시도 횟수는 1 이상이어야 합니다");
        }
        Objects.requireNonNull(leaseToken, "BRIEF lease token은 필수입니다");
    }
}
