package com.personal.baton.application.watch;

import com.personal.baton.application.watch.port.out.WatchMonitorOutboxPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchMonitorOutboxRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T05:00:00Z");

    @DisplayName("시작 복구는 기존 outbox와 다른 source namespace를 거절한다")
    @Test
    void rejectsChangedSourceNamespace() {
        WatchMonitorOutboxPort outboxPort = mock(WatchMonitorOutboxPort.class);
        WatchMonitorSource source = new WatchMonitorSource("study-pilot");
        when(outboxPort.hasMismatchedResourceReferencePrefix(
                source.resourceReferencePrefix()
        )).thenReturn(true);
        WatchMonitorOutboxRecoveryService service = new WatchMonitorOutboxRecoveryService(
                outboxPort,
                source,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(service::validateSourceNamespace)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WATCH source namespace가 기존 outbox resource reference와 다릅니다");
    }

    @DisplayName("outbound가 비활성이어도 호출된 source namespace 검사는 기존 outbox를 확인한다")
    @Test
    void validatesNamespaceForReceiverOnlyStartup() {
        WatchMonitorOutboxPort outboxPort = mock(WatchMonitorOutboxPort.class);
        WatchMonitorSource source = new WatchMonitorSource("study-pilot", false, true);
        when(outboxPort.hasMismatchedResourceReferencePrefix(
                source.resourceReferencePrefix()
        )).thenReturn(true);
        WatchMonitorOutboxRecoveryService service = new WatchMonitorOutboxRecoveryService(
                outboxPort,
                source,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(service::validateSourceNamespace)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("WATCH source namespace가 기존 outbox resource reference와 다릅니다");

        verify(outboxPort).hasMismatchedResourceReferencePrefix(
                source.resourceReferencePrefix()
        );
    }

    @DisplayName("시작 복구는 현재 시각부터 운영 설정 실패를 다시 전달 가능하게 한다")
    @Test
    void requeuesOperationalFailuresFromCurrentTime() {
        WatchMonitorOutboxPort outboxPort = mock(WatchMonitorOutboxPort.class);
        WatchMonitorOutboxRecoveryService service = new WatchMonitorOutboxRecoveryService(
                outboxPort,
                new WatchMonitorSource("study-pilot"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        service.requeueOperationalFailures();

        verify(outboxPort).requeueOperationalFailures(NOW);
    }
}
