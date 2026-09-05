package com.personal.baton.application.workspace.port.in;

import com.personal.baton.application.watch.WatchResourceHealth;
import com.personal.baton.application.watch.WatchCheckOutcome;
import java.time.Instant;
import java.util.UUID;

public interface InspectResourceHealthUseCase {
    Result inspect(UUID teamId, UUID seasonId, UUID resourceId, String accessKey);

    CheckResult requestCheck(UUID teamId, UUID seasonId, UUID resourceId, String accessKey);

    enum Availability { AVAILABLE, PENDING, STALE, UNAVAILABLE, NOT_MONITORED }

    enum MonitoringReason {
        INTEGRATION_DISABLED, MONITORING_PAUSED, SEASON_ENDED, RESOURCE_ARCHIVED,
        URL_NOT_ELIGIBLE, MONITOR_INACTIVE, SYNC_PENDING
    }

    record Result(UUID resourceId, WatchResourceHealth health, Availability availability,
                  Instant lastCheckedAt, boolean checkRequestAllowed,
                  WatchCheckOutcome lastOutcome, Integer consecutiveFailures,
                  MonitoringReason monitoringReason) { }

    enum CheckStatus { SCHEDULED, ALREADY_SCHEDULED, IN_PROGRESS }

    record CheckResult(UUID resourceId, CheckStatus status) { }
}
