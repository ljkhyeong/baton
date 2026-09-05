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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
        when(access.authorize(team, season, resource, "key")).thenReturn(Optional.of(snapshot));
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
        when(access.authorize(team, season, resource, "key")).thenReturn(Optional.of(snapshot),
                Optional.of(new WatchMonitorSnapshot(8, "resource", WatchMonitoringState.ACTIVE, "https://docs.example.com/new")));
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
        }
    }

    @Test
    @DisplayName("감시 중지 자료는 WATCH를 호출하지 않고 재점검도 거부한다")
    void doesNotCallInactive() {
        when(access.authorize(team, season, resource, "key")).thenReturn(Optional.empty());
        assertThat(service.inspect(team, season, resource, "key").availability()).isEqualTo(Availability.NOT_MONITORED);
        assertThatThrownBy(() -> service.requestCheck(team, season, resource, "key"))
                .isInstanceOf(ResourceCheckRequestException.class);
        verifyNoInteractions(watch);
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
