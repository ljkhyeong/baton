package com.personal.baton.application.watch.port.out;

import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.WatchCheckOutcome;
import com.personal.baton.application.watch.WatchResourceHealth;
import java.time.Instant;

public interface WatchMonitorInspectionPort {
    Inspection inspect(String resourceReference);

    CheckRequest requestCheck(String resourceReference);

    enum LookupStatus { FOUND, MISSING, UNAVAILABLE }

    record Inspection(LookupStatus status, long sourceRevision,
                      WatchMonitoringState monitoringState, WatchResourceHealth health,
                      Instant lastCheckedAt, WatchCheckOutcome lastOutcome, Integer consecutiveFailures) {
        public static Inspection unavailable() {
            return new Inspection(LookupStatus.UNAVAILABLE, 0, null, null, null, null, null);
        }
        public static Inspection missing() {
            return new Inspection(LookupStatus.MISSING, 0, null, null, null, null, null);
        }
    }

    enum CheckStatus { SCHEDULED, ALREADY_SCHEDULED, IN_PROGRESS, INACTIVE, RATE_LIMITED, UNAVAILABLE }

    record CheckRequest(CheckStatus status, Long retryAfterSeconds) {
        public static CheckRequest unavailable() {
            return new CheckRequest(CheckStatus.UNAVAILABLE, null);
        }
    }
}
