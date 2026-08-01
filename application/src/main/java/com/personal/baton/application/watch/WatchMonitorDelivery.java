package com.personal.baton.application.watch;

import java.util.Objects;
import java.util.UUID;

public record WatchMonitorDelivery(
        long sourceRevision,
        UUID eventId,
        UUID resourceId,
        String resourceReference,
        WatchMonitoringState monitoringState,
        String targetUrl,
        int attemptCount,
        UUID leaseToken
) {

    public WatchMonitorDelivery {
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("WATCH sourceRevision은 음수일 수 없습니다");
        }
        Objects.requireNonNull(eventId, "WATCH eventId는 필수입니다");
        Objects.requireNonNull(resourceId, "WATCH resourceId는 필수입니다");
        Objects.requireNonNull(resourceReference, "WATCH resourceReference는 필수입니다");
        Objects.requireNonNull(monitoringState, "WATCH monitoringState는 필수입니다");
        Objects.requireNonNull(leaseToken, "WATCH leaseToken은 필수입니다");
        if (attemptCount < 1) {
            throw new IllegalArgumentException("WATCH attemptCount는 1 이상이어야 합니다");
        }
        if (monitoringState == WatchMonitoringState.ACTIVE) {
            Objects.requireNonNull(targetUrl, "ACTIVE WATCH delivery의 targetUrl은 필수입니다");
        } else if (targetUrl != null) {
            throw new IllegalArgumentException("INACTIVE WATCH delivery에는 targetUrl을 둘 수 없습니다");
        }
    }
}
