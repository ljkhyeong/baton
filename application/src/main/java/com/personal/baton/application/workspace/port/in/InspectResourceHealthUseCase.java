package com.personal.baton.application.workspace.port.in;

import com.personal.baton.application.watch.WatchResourceHealth;
import java.time.Instant;
import java.util.UUID;

public interface InspectResourceHealthUseCase {
    Result inspect(UUID teamId, UUID seasonId, UUID resourceId, String accessKey);

    CheckResult requestCheck(UUID teamId, UUID seasonId, UUID resourceId, String accessKey);

    enum Availability { AVAILABLE, PENDING, STALE, UNAVAILABLE, NOT_MONITORED }

    record Result(UUID resourceId, WatchResourceHealth health, Availability availability,
                  Instant lastCheckedAt, boolean checkRequestAllowed) { }

    enum CheckStatus { SCHEDULED, ALREADY_SCHEDULED, IN_PROGRESS }

    record CheckResult(UUID resourceId, CheckStatus status) { }
}
