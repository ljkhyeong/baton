package com.personal.baton.application.workspace;

import com.personal.baton.application.watch.WatchMonitorSnapshot;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.WatchResourceHealth;
import com.personal.baton.application.watch.port.out.WatchMonitorInspectionPort;
import com.personal.baton.application.watch.port.out.WatchMonitorInspectionPort.LookupStatus;
import com.personal.baton.application.workspace.error.ResourceCheckRequestException;
import com.personal.baton.application.workspace.error.ResourceCheckRequestException.Reason;
import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WorkspaceResourceHealthService implements InspectResourceHealthUseCase {
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private final WorkspaceResourceHealthAccess access;
    private final WatchMonitorInspectionPort watch;
    private final Clock clock;

    public WorkspaceResourceHealthService(WorkspaceResourceHealthAccess access,
                                          WatchMonitorInspectionPort watch, Clock clock) {
        this.access = access;
        this.watch = watch;
        this.clock = clock;
    }

    @Override
    public Result inspect(UUID teamId, UUID seasonId, UUID resourceId, String accessKey) {
        var snapshot = access.authorize(teamId, seasonId, resourceId, accessKey);
        if (snapshot.isEmpty()) return unknown(resourceId, Availability.NOT_MONITORED);

        var remote = watch.inspect(snapshot.get().resourceReference());
        // 원격 조회 중 URL·시즌·공유 키가 바뀌면 이전 자료의 결과를 표시하지 않는다.
        if (!snapshot.equals(access.authorize(teamId, seasonId, resourceId, accessKey))) {
            return unknown(resourceId, Availability.PENDING);
        }
        if (remote.status() == LookupStatus.UNAVAILABLE) {
            return unknown(resourceId, Availability.UNAVAILABLE);
        }
        if (remote.status() == LookupStatus.MISSING
                || remote.sourceRevision() != snapshot.get().sourceRevision()
                || remote.monitoringState() != WatchMonitoringState.ACTIVE) {
            return unknown(resourceId, Availability.PENDING);
        }
        Instant now = clock.instant();
        Instant checked = remote.lastCheckedAt();
        if (checked == null) {
            return new Result(resourceId, WatchResourceHealth.UNKNOWN, Availability.PENDING, null, true);
        }
        if (checked.isAfter(now) || !checked.isAfter(now.minus(MAX_AGE))) {
            return new Result(resourceId, WatchResourceHealth.UNKNOWN, Availability.STALE, checked, true);
        }
        return new Result(resourceId, remote.health(), Availability.AVAILABLE, checked, true);
    }

    @Override
    public CheckResult requestCheck(UUID teamId, UUID seasonId, UUID resourceId, String accessKey) {
        WatchMonitorSnapshot snapshot = access.authorize(teamId, seasonId, resourceId, accessKey)
                .orElseThrow(() -> new ResourceCheckRequestException(Reason.INACTIVE, null));
        var remote = watch.inspect(snapshot.resourceReference());
        if (remote.status() == LookupStatus.MISSING
                || (remote.status() == LookupStatus.FOUND
                && remote.monitoringState() != WatchMonitoringState.ACTIVE)) {
            throw new ResourceCheckRequestException(Reason.INACTIVE, null);
        }
        if (remote.status() != LookupStatus.FOUND || remote.sourceRevision() != snapshot.sourceRevision()
                || access.authorize(teamId, seasonId, resourceId, accessKey).filter(snapshot::equals).isEmpty()) {
            throw new ResourceCheckRequestException(Reason.UNAVAILABLE, null);
        }
        var result = watch.requestCheck(snapshot.resourceReference());
        return switch (result.status()) {
            case SCHEDULED, ALREADY_SCHEDULED, IN_PROGRESS ->
                    new CheckResult(resourceId, CheckStatus.valueOf(result.status().name()));
            case INACTIVE -> throw new ResourceCheckRequestException(Reason.INACTIVE, null);
            case RATE_LIMITED -> throw new ResourceCheckRequestException(Reason.RATE_LIMITED,
                    result.retryAfterSeconds());
            case UNAVAILABLE -> throw new ResourceCheckRequestException(Reason.UNAVAILABLE, null);
        };
    }

    private Result unknown(UUID resourceId, Availability availability) {
        return new Result(resourceId, WatchResourceHealth.UNKNOWN, availability, null, false);
    }
}
