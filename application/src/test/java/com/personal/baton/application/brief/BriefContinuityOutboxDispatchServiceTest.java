package com.personal.baton.application.brief;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.brief.port.out.BriefContinuityClient;
import com.personal.baton.application.brief.port.out.BriefContinuityOutboxPort;
import com.personal.baton.application.workspace.port.in.ContinuitySignalSeverity;
import com.personal.baton.application.workspace.port.in.ContinuitySignalType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BriefContinuityOutboxDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T03:00:00Z");

    @DisplayName("BRIEF 전달 결과는 완료, 재시도, 영구 실패 상태로 각각 반영한다")
    @Test
    void recordsDeliveryOutcomes() {
        BriefContinuityOutboxPort outboxPort = mock(BriefContinuityOutboxPort.class);
        BriefContinuityClient client = mock(BriefContinuityClient.class);
        BriefContinuityDelivery delivered = delivery(1);
        BriefContinuityDelivery retryable = delivery(2);
        BriefContinuityDelivery permanent = delivery(3);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any(Duration.class)))
                .thenReturn(List.of(delivered))
                .thenReturn(List.of(retryable))
                .thenReturn(List.of(permanent));
        when(client.deliver(delivered))
                .thenReturn(BriefContinuityClient.DeliveryResult.delivered("HTTP_202"));
        when(client.deliver(retryable))
                .thenReturn(BriefContinuityClient.DeliveryResult.retryable("HTTP_503"));
        when(client.deliver(permanent))
                .thenReturn(BriefContinuityClient.DeliveryResult.permanentFailure("HTTP_422"));
        when(outboxPort.markDelivered(
                delivered.outboxId(),
                delivered.leaseToken(),
                NOW,
                "HTTP_202"
        )).thenReturn(true);
        BriefContinuityOutboxDispatchService service = new BriefContinuityOutboxDispatchService(
                outboxPort,
                client,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThat(service.dispatchPending().deliveredCount()).isOne();
        assertThat(service.dispatchPending().failedCount()).isOne();
        assertThat(service.dispatchPending().failedCount()).isOne();

        verify(outboxPort).markRetry(
                retryable.outboxId(),
                retryable.leaseToken(),
                NOW,
                "HTTP_503"
        );
        verify(outboxPort).markFailed(
                permanent.outboxId(),
                permanent.leaseToken(),
                NOW,
                "HTTP_422"
        );
    }

    private BriefContinuityDelivery delivery(long outboxId) {
        UUID signalId = UUID.randomUUID();
        return new BriefContinuityDelivery(
                outboxId,
                new BriefContinuityEvent(
                        UUID.randomUUID(),
                        ContinuitySignalType.ROLE_UNASSIGNED,
                        2,
                        ContinuitySignalSeverity.CRITICAL,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "baton-continuity:" + signalId,
                        1,
                        NOW,
                        BriefContinuityEvent.State.ACTIVE
                ),
                1,
                UUID.randomUUID()
        );
    }
}
