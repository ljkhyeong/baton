package com.personal.baton.application.workspace;

import com.personal.baton.application.watch.WatchMonitorSnapshot;
import com.personal.baton.application.watch.WatchMonitorSource;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.WatchMonitorEligibilityPolicy;
import com.personal.baton.application.watch.port.out.WatchMonitorSnapshotPort;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase.MonitoringReason;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class WorkspaceResourceHealthAccess {
    private final WorkspaceScopeAuthorizer scopeAuthorizer;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceRecordsRepository recordsRepository;
    private final WatchMonitorSnapshotPort snapshots;
    private final WatchMonitorSource source;
    private final WatchMonitorEligibilityPolicy eligibility = new WatchMonitorEligibilityPolicy();

    public record Authorization(WatchMonitorSnapshot snapshot, MonitoringReason reason) { }

    WorkspaceResourceHealthAccess(WorkspaceScopeAuthorizer scopeAuthorizer,
                                  WorkspaceRoleResolver roleResolver,
                                  WorkspaceRecordsRepository recordsRepository,
                                  WatchMonitorSnapshotPort snapshots, WatchMonitorSource source) {
        this.scopeAuthorizer = scopeAuthorizer;
        this.roleResolver = roleResolver;
        this.recordsRepository = recordsRepository;
        this.snapshots = snapshots;
        this.source = source;
    }

    @Transactional(readOnly = true)
    public Authorization authorize(UUID teamId, UUID seasonId,
                                                   UUID resourceId, String accessKey) {
        WorkspaceScope scope = scopeAuthorizer.authorizeRead(teamId, seasonId, accessKey);
        var resource = recordsRepository.findRoleResourceById(resourceId)
                .orElseThrow(this::notFound);
        roleResolver.requireRole(teamId, seasonId, resource.getRoleId(), this::notFound);
        if (!source.enabled()) return new Authorization(null, MonitoringReason.INTEGRATION_DISABLED);
        if (!source.monitoringEnabled()) return new Authorization(null, MonitoringReason.MONITORING_PAUSED);
        if (scope.season().isEnded()) return new Authorization(null, MonitoringReason.SEASON_ENDED);
        if (resource.getArchivedAt() != null) return new Authorization(null, MonitoringReason.RESOURCE_ARCHIVED);
        if (!eligibility.isEligible(resource.getUrl())) return new Authorization(null, MonitoringReason.URL_NOT_ELIGIBLE);
        var snapshot = snapshots.findLatestMonitor(resourceId).orElse(null);
        if (snapshot == null || !snapshot.resourceReference().equals(source.resourceReference(resourceId))) {
            return new Authorization(null, MonitoringReason.SYNC_PENDING);
        }
        if (snapshot.monitoringState() != WatchMonitoringState.ACTIVE) {
            return new Authorization(null, MonitoringReason.MONITOR_INACTIVE);
        }
        if (!resource.getUrl().equals(snapshot.targetUrl())) {
            return new Authorization(null, MonitoringReason.SYNC_PENDING);
        }
        return new Authorization(snapshot, null);
    }

    private WorkspaceNotFoundException notFound() {
        return new WorkspaceNotFoundException("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다");
    }
}
