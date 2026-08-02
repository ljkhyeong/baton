package com.personal.baton.application.watch;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WatchMonitorChange(
        UUID eventId,
        UUID resourceId,
        String resourceReference,
        WatchMonitoringState monitoringState,
        String targetUrl,
        Instant occurredAt
) {

    public WatchMonitorChange {
        Objects.requireNonNull(eventId, "WATCH eventId는 필수입니다");
        Objects.requireNonNull(resourceId, "WATCH resourceId는 필수입니다");
        Objects.requireNonNull(resourceReference, "WATCH resourceReference는 필수입니다");
        Objects.requireNonNull(monitoringState, "WATCH monitoringState는 필수입니다");
        Objects.requireNonNull(occurredAt, "WATCH occurredAt은 필수입니다");
        if (monitoringState == WatchMonitoringState.ACTIVE) {
            Objects.requireNonNull(targetUrl, "ACTIVE WATCH snapshot의 targetUrl은 필수입니다");
        } else if (targetUrl != null) {
            throw new IllegalArgumentException("INACTIVE WATCH snapshot에는 targetUrl을 둘 수 없습니다");
        }
    }
}
