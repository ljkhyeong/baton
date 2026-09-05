package com.personal.baton.application.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.personal.baton.application.watch.WatchMonitorSnapshot;
import com.personal.baton.application.watch.WatchMonitorSource;
import com.personal.baton.application.watch.WatchMonitoringState;
import com.personal.baton.application.watch.port.out.WatchMonitorSnapshotPort;
import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase.MonitoringReason;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Season;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkspaceResourceHealthAccessTest {
    @ParameterizedTest
    @CsvSource(value = {
            "ready,",
            "integration,INTEGRATION_DISABLED",
            "paused,MONITORING_PAUSED",
            "ended,SEASON_ENDED",
            "archived,RESOURCE_ARCHIVED",
            "url,URL_NOT_ELIGIBLE",
            "inactive,MONITOR_INACTIVE",
            "missing,SYNC_PENDING",
            "changed,SYNC_PENDING",
            "namespace,SYNC_PENDING"
    })
    @DisplayName("현재 자료의 제외 사유와 등록 대기를 구분하고 일치하는 활성 스냅샷만 조회 대상으로 반환한다")
    void classifiesMonitoring(String scenario, MonitoringReason expected) {
        UUID teamId = UUID.randomUUID(), seasonId = UUID.randomUUID(), resourceId = UUID.randomUUID(), roleId = UUID.randomUUID();
        var authorizer = mock(WorkspaceScopeAuthorizer.class);
        var resolver = mock(WorkspaceRoleResolver.class);
        var records = mock(WorkspaceRecordsRepository.class);
        var snapshots = mock(WatchMonitorSnapshotPort.class);
        var season = mock(Season.class);
        var resource = mock(RoleResource.class);
        var source = new WatchMonitorSource("test", !scenario.equals("integration"), !scenario.equals("paused"));
        when(authorizer.authorizeRead(teamId, seasonId, "key")).thenReturn(new WorkspaceScope(null, season));
        when(records.findRoleResourceById(resourceId)).thenReturn(Optional.of(resource));
        when(resource.getRoleId()).thenReturn(roleId);
        when(resource.getUrl()).thenReturn(scenario.equals("url") ? "https://docs.example.com/guide?token=value" : "https://docs.example.com/guide");
        when(resource.getArchivedAt()).thenReturn(scenario.equals("archived") ? Instant.parse("2026-09-05T00:00:00Z") : null);
        when(season.isEnded()).thenReturn(scenario.equals("ended"));
        var snapshot = new WatchMonitorSnapshot(7,
                scenario.equals("namespace") ? "other" : source.resourceReference(resourceId),
                scenario.equals("inactive") ? WatchMonitoringState.INACTIVE : WatchMonitoringState.ACTIVE,
                scenario.equals("inactive") ? null : scenario.equals("changed")
                        ? "https://docs.example.com/previous" : "https://docs.example.com/guide");
        when(snapshots.findLatestMonitor(resourceId)).thenReturn(scenario.equals("missing") ? Optional.empty() : Optional.of(snapshot));
        var access = new WorkspaceResourceHealthAccess(authorizer, resolver, records, snapshots, source);

        var result = access.authorize(teamId, seasonId, resourceId, "key");

        assertThat(result.reason()).isEqualTo(expected);
        assertThat(result.snapshot()).isEqualTo(expected == null ? snapshot : null);
        verify(authorizer).authorizeRead(teamId, seasonId, "key");
        verify(resolver).requireRole(eq(teamId), eq(seasonId), eq(roleId), any());
    }
}
