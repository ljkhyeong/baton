package com.personal.baton.application.workspace;

import com.personal.baton.application.watch.WatchMonitorSnapshot;
import com.personal.baton.application.watch.WatchMonitorSource;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.port.out.WatchMonitorSnapshotPort;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import java.util.Optional;
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
    public Optional<WatchMonitorSnapshot> authorize(UUID teamId, UUID seasonId,
                                                   UUID resourceId, String accessKey) {
        WorkspaceScope scope = scopeAuthorizer.authorizeRead(teamId, seasonId, accessKey);
        var resource = recordsRepository.findRoleResourceById(resourceId)
                .orElseThrow(this::notFound);
        roleResolver.requireRole(teamId, seasonId, resource.getRoleId(), this::notFound);
        if (!source.enabled() || !source.monitoringEnabled()
                || scope.season().isEnded() || resource.getArchivedAt() != null) {
            return Optional.empty();
        }
        return snapshots.findLatestMonitor(resourceId)
                .filter(snapshot -> snapshot.resourceReference().equals(source.resourceReference(resourceId)))
                .filter(snapshot -> snapshot.monitoringState() == WatchMonitoringState.ACTIVE)
                .filter(snapshot -> resource.getUrl().equals(snapshot.targetUrl()));
    }

    private WorkspaceNotFoundException notFound() {
        return new WorkspaceNotFoundException("ROLE_RESOURCE_NOT_FOUND", "자료를 찾을 수 없습니다");
    }
}
