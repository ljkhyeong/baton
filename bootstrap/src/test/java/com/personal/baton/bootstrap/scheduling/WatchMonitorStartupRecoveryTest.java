package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.personal.baton.application.watch.port.in.RecoverWatchMonitorOutboxUseCase;
import com.personal.baton.bootstrap.config.WatchEventReceiverProperties;
import com.personal.baton.bootstrap.config.WatchIntegrationProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class WatchMonitorStartupRecoveryTest {

    private static final String RECEIVER_TOKEN =
            "receiver-token-with-at-least-32-characters";

    @DisplayName("WATCH 양방향 연동이 모두 비활성이면 outbox를 검사하지 않는다")
    @Test
    void skipRecoveryWhenBothDirectionsAreDisabled() {
        RecoverWatchMonitorOutboxUseCase recover = mock(RecoverWatchMonitorOutboxUseCase.class);
        WatchMonitorStartupRecovery startupRecovery = startupRecovery(recover, false, false);

        startupRecovery.recoverOnStartup();

        verifyNoInteractions(recover);
    }

    @DisplayName("WATCH 이벤트 수신만 활성화해도 기존 outbox namespace가 다르면 시작을 거부한다")
    @Test
    void rejectMismatchedNamespaceWhenOnlyReceiverIsEnabled() {
        RecoverWatchMonitorOutboxUseCase recover = mock(RecoverWatchMonitorOutboxUseCase.class);
        doThrow(new IllegalStateException("namespace mismatch"))
                .when(recover)
                .validateSourceNamespace();
        WatchMonitorStartupRecovery startupRecovery = startupRecovery(recover, false, true);

        assertThatThrownBy(startupRecovery::recoverOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("namespace mismatch");

        verify(recover).validateSourceNamespace();
        verify(recover, never()).requeueOperationalFailures();
    }

    @DisplayName("WATCH outbound 연동은 namespace를 검사한 뒤 운영 설정 실패를 재처리한다")
    @Test
    void validateAndRequeueWhenOutboundIsEnabled() {
        RecoverWatchMonitorOutboxUseCase recover = mock(RecoverWatchMonitorOutboxUseCase.class);
        when(recover.requeueOperationalFailures()).thenReturn(1);
        WatchMonitorStartupRecovery startupRecovery = startupRecovery(recover, true, false);

        startupRecovery.recoverOnStartup();

        InOrder recoveryOrder = inOrder(recover);
        recoveryOrder.verify(recover).validateSourceNamespace();
        recoveryOrder.verify(recover).requeueOperationalFailures();
    }

    private WatchMonitorStartupRecovery startupRecovery(
            RecoverWatchMonitorOutboxUseCase recover,
            boolean outboundEnabled,
            boolean receiverEnabled
    ) {
        return new WatchMonitorStartupRecovery(
                recover,
                new WatchIntegrationProperties(
                        outboundEnabled,
                        true,
                        "https://watch.internal",
                        "outbound-token-with-at-least-32-characters",
                        "study-pilot",
                        null,
                        null
                ),
                new WatchEventReceiverProperties(receiverEnabled, RECEIVER_TOKEN)
        );
    }
}
