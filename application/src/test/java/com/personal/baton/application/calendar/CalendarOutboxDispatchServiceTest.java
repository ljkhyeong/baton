package com.personal.baton.application.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CalendarOutboxDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @Test
    @DisplayName("응답을 잃은 같은 CAL 아웃박스 행은 재전송 후 중복으로 완료된다")
    void completesSameDeliveryAfterLostResponse() {
        CalendarOutboxPort outboxPort = mock(CalendarOutboxPort.class);
        CalendarSnapshotClient client = mock(CalendarSnapshotClient.class);
        CalendarSnapshot snapshot = snapshot(31);
        CalendarSnapshotDelivery first = delivery(snapshot, 1);
        CalendarSnapshotDelivery retry = delivery(snapshot, 2);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any()))
                .thenReturn(List.of(first))
                .thenReturn(List.of(retry));
        when(client.deliver(snapshot))
                .thenReturn(CalendarSnapshotClient.DeliveryResult.retryable("CAL_NETWORK_FAILURE"))
                .thenReturn(CalendarSnapshotClient.DeliveryResult.delivered("DUPLICATE"));
        when(outboxPort.markDelivered(31, retry.leaseToken(), NOW, "DUPLICATE"))
                .thenReturn(true);

        var firstResult = service(outboxPort, client).dispatchPending();
        var retryResult = service(outboxPort, client).dispatchPending();

        assertThat(firstResult.failedCount()).isEqualTo(1);
        assertThat(retryResult.deliveredCount()).isEqualTo(1);
        verify(outboxPort).markRetry(
                31,
                first.leaseToken(),
                NOW.plusSeconds(10),
                "CAL_NETWORK_FAILURE"
        );
        verify(outboxPort).markDelivered(31, retry.leaseToken(), NOW, "DUPLICATE");
    }

    @Test
    @DisplayName("CAL 계약 충돌은 새 개정 번호를 만들지 않고 현재 아웃박스 행을 실패로 끝낸다")
    void failsPermanentContractConflict() {
        CalendarOutboxPort outboxPort = mock(CalendarOutboxPort.class);
        CalendarSnapshotClient client = mock(CalendarSnapshotClient.class);
        CalendarSnapshotDelivery delivery = delivery(snapshot(41), 1);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any()))
                .thenReturn(List.of(delivery));
        when(client.deliver(delivery.snapshot()))
                .thenReturn(CalendarSnapshotClient.DeliveryResult.permanentFailure(
                        "SOURCE_REVISION_CONFLICT"
                ));

        var result = service(outboxPort, client).dispatchPending();

        assertThat(result.failedCount()).isEqualTo(1);
        verify(outboxPort).markFailed(
                41,
                delivery.leaseToken(),
                NOW,
                "SOURCE_REVISION_CONFLICT"
        );
    }

    private CalendarOutboxDispatchService service(
            CalendarOutboxPort outboxPort,
            CalendarSnapshotClient client
    ) {
        return new CalendarOutboxDispatchService(
                outboxPort,
                client,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private CalendarSnapshotDelivery delivery(CalendarSnapshot snapshot, int attemptCount) {
        return new CalendarSnapshotDelivery(snapshot, attemptCount, UUID.randomUUID());
    }

    private CalendarSnapshot snapshot(int revision) {
        return new CalendarSnapshot(
                UUID.randomUUID(),
                NOW,
                UUID.randomUUID(),
                UUID.randomUUID(),
                revision,
                CalendarSnapshot.Status.ACTIVE,
                "회차 일정",
                null,
                null,
                new CalendarSnapshot.AllDay(
                        LocalDate.of(2026, 8, 26),
                        LocalDate.of(2026, 8, 27)
                ),
                NOW
        );
    }
}
