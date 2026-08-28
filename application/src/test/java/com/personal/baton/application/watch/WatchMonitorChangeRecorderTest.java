package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import com.personal.baton.domain.workspace.RoleResource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchMonitorChangeRecorderTest {

    private static final Instant NOW = Instant.parse("2026-08-01T03:00:00Z");
    private static final UUID RESOURCE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000301"
    );
    private final WatchMonitorOutboxPort outboxPort = mock(WatchMonitorOutboxPort.class);
    private final WatchMonitorChangeRecorder recorder = new WatchMonitorChangeRecorder(
            outboxPort,
            new WatchMonitorSource("study-pilot"),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @DisplayName("감시 적격 자료 생성은 안정적인 reference와 ACTIVE snapshot을 기록한다")
    @Test
    void recordsEligibleCreation() {
        when(outboxPort.appendIfChanged(any())).thenReturn(true);

        recorder.recordCreated(resource("https://docs.example.com/study"));

        ArgumentCaptor<WatchMonitorChange> captor = ArgumentCaptor.forClass(
                WatchMonitorChange.class
        );
        verify(outboxPort).appendIfChanged(captor.capture());
        WatchMonitorChange change = captor.getValue();
        assertThat(change.resourceReference()).isEqualTo(
                "baton-manager:study-pilot:role-resource:" + RESOURCE_ID
        );
        assertThat(change.monitoringState()).isEqualTo(WatchMonitoringState.ACTIVE);
        assertThat(change.targetUrl()).isEqualTo("https://docs.example.com/study");
        assertThat(change.occurredAt()).isEqualTo(NOW);
    }

    @DisplayName("처음부터 감시 비적격인 자료는 WATCH outbox를 만들지 않는다")
    @Test
    void skipsIneligibleCreation() {
        recorder.recordCreated(resource("https://docs.example.com/study?token=secret"));

        verify(outboxPort, never()).appendIfChanged(any());
    }

    @DisplayName("WATCH 연동이 꺼져 있으면 기본 namespace로 미래 전달 snapshot을 쌓지 않는다")
    @Test
    void skipsChangesWhileIntegrationIsDisabled() {
        WatchMonitorOutboxPort disabledOutbox = mock(WatchMonitorOutboxPort.class);
        WatchMonitorChangeRecorder disabledRecorder = new WatchMonitorChangeRecorder(
                disabledOutbox,
                new WatchMonitorSource("primary", false, false),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        disabledRecorder.recordCreated(resource("https://docs.example.com/study"));

        verify(disabledOutbox, never()).appendIfChanged(any());
    }

    @DisplayName("적격 URL이 비적격 URL로 바뀌면 이전 monitor를 INACTIVE로 전환한다")
    @Test
    void deactivatesWhenUpdatedUrlBecomesIneligible() {
        when(outboxPort.appendIfChanged(any())).thenReturn(true);
        RoleResource resource = resource("https://docs.example.com/study");
        resource.update(
                resource.getRoleId(),
                resource.getTitle(),
                "https://docs.example.com/study?token=secret",
                resource.getDescription()
        );

        recorder.recordUpdated("https://docs.example.com/study", resource);

        ArgumentCaptor<WatchMonitorChange> captor = ArgumentCaptor.forClass(
                WatchMonitorChange.class
        );
        verify(outboxPort).appendIfChanged(captor.capture());
        assertThat(captor.getValue().monitoringState())
                .isEqualTo(WatchMonitoringState.INACTIVE);
        assertThat(captor.getValue().targetUrl()).isNull();
    }

    @DisplayName("역할 자료를 보관하면 적격 URL의 WATCH monitor를 비활성화한다")
    @Test
    void deactivatesWhenRoleResourceIsArchived() {
        when(outboxPort.appendIfChanged(any())).thenReturn(true);
        RoleResource resource = resource("https://docs.example.com/study");
        resource.updateArchive(true, NOW);

        recorder.recordUpdated("https://docs.example.com/study", resource);

        ArgumentCaptor<WatchMonitorChange> captor = ArgumentCaptor.forClass(
                WatchMonitorChange.class
        );
        verify(outboxPort).appendIfChanged(captor.capture());
        assertThat(captor.getValue().monitoringState())
                .isEqualTo(WatchMonitoringState.INACTIVE);
        assertThat(captor.getValue().targetUrl()).isNull();
    }

    @DisplayName("시즌 종료는 URL 적격 여부와 관계없이 모든 자료의 monitor를 비활성화한다")
    @Test
    void deactivatesEveryResourceWhenSeasonEnds() {
        when(outboxPort.appendIfChanged(any())).thenReturn(true);

        int appendedCount = recorder.recordSeasonState(List.of(
                resource("https://docs.example.com/study"),
                RoleResource.create(
                        UUID.fromString("00000000-0000-0000-0000-000000000302"),
                        UUID.randomUUID(),
                        "비공개 자료",
                        "https://docs.example.com/study?token=secret",
                        null,
                        NOW
                )
        ), true);

        assertThat(appendedCount).isEqualTo(2);
        ArgumentCaptor<WatchMonitorChange> captor = ArgumentCaptor.forClass(
                WatchMonitorChange.class
        );
        verify(outboxPort, org.mockito.Mockito.times(2)).appendIfChanged(captor.capture());
        assertThat(captor.getAllValues())
                .allMatch(change -> change.monitoringState() == WatchMonitoringState.INACTIVE);
    }

    @DisplayName("reconciliation은 현재 후보 확인과 snapshot 저장을 같은 출력 port 경계에 맡긴다")
    @Test
    void delegatesReconciliationWithExpectedCandidate() {
        WatchMonitorCandidate candidate = new WatchMonitorCandidate(
                RESOURCE_ID,
                "https://docs.example.com/study",
                false
        );
        when(outboxPort.appendReconciledIfCurrent(eq(candidate), any())).thenReturn(true);

        assertThat(recorder.reconcile(candidate)).isTrue();

        ArgumentCaptor<WatchMonitorChange> captor = ArgumentCaptor.forClass(
                WatchMonitorChange.class
        );
        verify(outboxPort).appendReconciledIfCurrent(eq(candidate), captor.capture());
        assertThat(captor.getValue().monitoringState()).isEqualTo(WatchMonitoringState.ACTIVE);
        assertThat(captor.getValue().targetUrl()).isEqualTo(candidate.targetUrl());
    }

    @DisplayName("점검 중단 모드의 reconciliation은 연결을 끊지 않고 INACTIVE를 기록한다")
    @Test
    void reconcilesInactiveWhileMonitoringIsDisabled() {
        WatchMonitorOutboxPort decommissioningOutbox = mock(WatchMonitorOutboxPort.class);
        WatchMonitorChangeRecorder decommissioningRecorder = new WatchMonitorChangeRecorder(
                decommissioningOutbox,
                new WatchMonitorSource("study-pilot", true, false),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        WatchMonitorCandidate candidate = new WatchMonitorCandidate(
                RESOURCE_ID,
                "https://docs.example.com/study",
                false
        );

        decommissioningRecorder.reconcile(candidate);

        ArgumentCaptor<WatchMonitorChange> captor = ArgumentCaptor.forClass(
                WatchMonitorChange.class
        );
        verify(decommissioningOutbox).appendReconciledIfCurrent(
                eq(candidate),
                captor.capture()
        );
        assertThat(captor.getValue().monitoringState()).isEqualTo(WatchMonitoringState.INACTIVE);
        assertThat(captor.getValue().targetUrl()).isNull();
    }

    private RoleResource resource(String url) {
        return RoleResource.create(
                RESOURCE_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000399"),
                "스터디 자료",
                url,
                "함께 보는 자료",
                NOW
        );
    }
}
