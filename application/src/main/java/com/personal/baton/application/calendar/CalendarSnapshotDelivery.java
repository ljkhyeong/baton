package com.personal.baton.application.calendar;

import java.util.Objects;
import java.util.UUID;

public record CalendarSnapshotDelivery(
        CalendarSnapshot snapshot,
        int attemptCount,
        UUID leaseToken
) {

    public CalendarSnapshotDelivery {
        Objects.requireNonNull(snapshot, "CAL snapshot은 필수입니다");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("CAL 전달 시도 횟수는 1 이상이어야 합니다");
        }
        Objects.requireNonNull(leaseToken, "CAL lease token은 필수입니다");
    }
}
