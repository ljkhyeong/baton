package com.personal.baton.application.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.personal.baton.application.watch.*;
import com.personal.baton.application.watch.port.out.WatchMonitorInspectionPort;
import com.personal.baton.application.watch.port.out.WatchMonitorInspectionPort.*;
import com.personal.baton.application.workspace.error.ResourceCheckRequestException;
import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase.Availability;
import com.personal.baton.application.workspace.port.in.InspectResourceHealthUseCase.MonitoringReason;
import com.personal.baton.application.workspace.WorkspaceResourceHealthAccess.Authorization;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;

class WorkspaceResourceHealthServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-05T01:00:00Z");
    private final UUID team = UUID.randomUUID(), season = UUID.randomUUID(), resource = UUID.randomUUID();
    private final WatchMonitorSnapshot snapshot = new WatchMonitorSnapshot(7, "resource", WatchMonitoringState.ACTIVE, "https://docs.example.com");
    private WorkspaceResourceHealthAccess access;
    private WatchMonitorInspectionPort watch;
    private WorkspaceResourceHealthService service;

    @BeforeEach
    void setUp() {
        access = mock(WorkspaceResourceHealthAccess.class);
        watch = mock(WatchMonitorInspectionPort.class);
        service = new WorkspaceResourceHealthService(access, watch, Clock.fixed(NOW, ZoneOffset.UTC));
        when(access.authorize(team, season, resource, "key")).thenReturn(new Authorization(snapshot, null));
    }

    static Stream<Arguments> freshnessCases() {
        return Stream.of(Arguments.of(NOW.minusSeconds(299), Availability.AVAILABLE),
                Arguments.of(NOW.minusSeconds(300), Availability.STALE),
                Arguments.of(NOW.plusSeconds(1), Availability.STALE),
                Arguments.of(null, Availability.PENDING));
    }

    @ParameterizedTest
    @MethodSource("freshnessCases")
    @DisplayName("최근 점검 5분 경계와 미래 시각 및 미점검에는 이전 실패 원인과 횟수를 표시하지 않는다")
    void freshness(Instant checked, Availability availability) {
        when(watch.inspect("resource")).thenReturn(new Inspection(LookupStatus.FOUND, 7,
                WatchMonitoringState.ACTIVE, WatchResourceHealth.BROKEN, checked, WatchCheckOutcome.DNS_FAILURE, 3));
        var result = service.inspect(team, season, resource, "key");
        assertThat(result.availability()).isEqualTo(availability);
        assertThat(result.health()).isEqualTo(availability == Availability.AVAILABLE
                ? WatchResourceHealth.BROKEN : WatchResourceHealth.UNKNOWN);
        assertThat(result.lastOutcome()).isEqualTo(availability == Availability.AVAILABLE ? WatchCheckOutcome.DNS_FAILURE : null);
        assertThat(result.consecutiveFailures()).isEqualTo(availability == Availability.AVAILABLE ? 3 : null);
        assertThat(result.checkRequestAllowed()).isTrue();
    }

    @Test
    @DisplayName("원격 조회 중 URL 스냅샷이 바뀌면 이전 URL의 정상 결과를 버린다")
    void rejectsChangedSnapshot() {
        when(access.authorize(team, season, resource, "key")).thenReturn(new Authorization(snapshot, null),
                new Authorization(new WatchMonitorSnapshot(8, "resource", WatchMonitoringState.ACTIVE, "https://docs.example.com/new"), null));
        when(watch.inspect("resource")).thenReturn(new Inspection(LookupStatus.FOUND, 7,
                WatchMonitoringState.ACTIVE, WatchResourceHealth.HEALTHY, NOW, WatchCheckOutcome.SUCCESS, 0));
        assertThat(service.inspect(team, season, resource, "key").availability()).isEqualTo(Availability.PENDING);
    }

    @Test
    @DisplayName("모니터 누락과 리비전 불일치 및 원격 장애는 자료 연결 실패로 판정하지 않는다")
    void unknownOnRemoteFailure() {
        when(watch.inspect("resource")).thenReturn(Inspection.missing(), Inspection.unavailable(),
                new Inspection(LookupStatus.FOUND, 8, WatchMonitoringState.ACTIVE, WatchResourceHealth.HEALTHY, NOW, WatchCheckOutcome.SUCCESS, 0));
        for (Availability expected : new Availability[]{Availability.PENDING, Availability.UNAVAILABLE, Availability.PENDING}) {
            var result = service.inspect(team, season, resource, "key");
            assertThat(result.availability()).isEqualTo(expected);
            assertThat(result.health()).isEqualTo(WatchResourceHealth.UNKNOWN);
            assertThat(result.checkRequestAllowed()).isFalse();
            assertThat(result.lastOutcome()).isNull();
            assertThat(result.consecutiveFailures()).isNull();
            assertThat(result.monitoringReason()).isEqualTo(expected == Availability.PENDING ? MonitoringReason.SYNC_PENDING : null);
        }
    }

    @ParameterizedTest
    @EnumSource(MonitoringReason.class)
    @DisplayName("감시 제외 사유와 동기화 대기를 구분하고 원격 조회와 재점검을 보내지 않는다")
    void doesNotCallInactive(MonitoringReason reason) {
        when(access.authorize(team, season, resource, "key")).thenReturn(new Authorization(null, reason));
        var result = service.inspect(team, season, resource, "key");
        assertThat(result.availability()).isEqualTo(reason == MonitoringReason.SYNC_PENDING ? Availability.PENDING : Availability.NOT_MONITORED);
        assertThat(result.monitoringReason()).isEqualTo(reason);
        assertThat(result.checkRequestAllowed()).isFalse();
        assertThatThrownBy(() -> service.requestCheck(team, season, resource, "key"))
                .isInstanceOfSatisfying(ResourceCheckRequestException.class, exception ->
                        assertThat(exception.reason()).isEqualTo(reason == MonitoringReason.SYNC_PENDING
                                ? ResourceCheckRequestException.Reason.UNAVAILABLE : ResourceCheckRequestException.Reason.INACTIVE));
        verifyNoInteractions(watch);
    }

    @Test
    @DisplayName("조회 도중 자료가 보관되면 새 제외 사유를 반환하고 이전 상태는 버린다")
    void usesCurrentExclusionReason() {
        when(access.authorize(team, season, resource, "key")).thenReturn(new Authorization(snapshot, null),
                new Authorization(null, MonitoringReason.RESOURCE_ARCHIVED));
        when(watch.inspect("resource")).thenReturn(new Inspection(LookupStatus.FOUND, 7,
                WatchMonitoringState.ACTIVE, WatchResourceHealth.HEALTHY, NOW, WatchCheckOutcome.SUCCESS, 0));
        var result = service.inspect(team, season, resource, "key");
        assertThat(result.availability()).isEqualTo(Availability.NOT_MONITORED);
        assertThat(result.monitoringReason()).isEqualTo(MonitoringReason.RESOURCE_ARCHIVED);
        assertThat(result.lastOutcome()).isNull();
    }
    @Test
    @DisplayName("URL 변경이 WATCH에 아직 전달되지 않았으면 이전 주소의 재점검을 접수하지 않는다")
    void waitsForCurrentSourceBeforeRequest() {
        when(watch.inspect("resource")).thenReturn(new Inspection(LookupStatus.FOUND, 6,
                WatchMonitoringState.ACTIVE, WatchResourceHealth.HEALTHY, NOW, WatchCheckOutcome.SUCCESS, 0));
        assertThatThrownBy(() -> service.requestCheck(team, season, resource, "key"))
                .isInstanceOf(ResourceCheckRequestException.class);
        verify(watch, never()).requestCheck(anyString());
    }

}
