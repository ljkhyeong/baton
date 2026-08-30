package com.personal.baton.application.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.calendar.port.out.CalendarOutboxPort;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import com.personal.baton.application.calendar.port.out.CalendarSeasonMetadataClient;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

@Tag("policy")
class CalendarOutboxDispatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T12:00:00Z");

    @Test
    @DisplayName("일정 HTTP 전달이 끝난 뒤에 이름을 임대해 이전 요청 중 임대 시간이 소진되지 않는다")
    void claimsMetadataAfterScheduleRequestCompletes() {
        var outbox = mock(CalendarOutboxPort.class);
        var schedules = mock(CalendarSnapshotClient.class);
        var metadataClient = mock(CalendarSeasonMetadataClient.class);
        var schedule = snapshot(7);
        var metadata = new CalendarSeasonMetadata(schedule.seasonId(), 7, "여름 시즌");
        var first = delivery(schedule, 1);
        var second = new CalendarDelivery(metadata, 1, UUID.randomUUID());
        when(outbox.claimPending(anyInt(), eq(NOW), any(), eq(false))).thenReturn(List.of(first));
        when(outbox.claimPending(anyInt(), eq(NOW), any(), eq(true))).thenReturn(List.of(second));
        when(schedules.deliver(schedule)).thenReturn(CalendarSnapshotClient.DeliveryResult.delivered("APPLIED"));
        when(metadataClient.deliver(metadata))
                .thenReturn(CalendarSnapshotClient.DeliveryResult.delivered("SEASON_METADATA_ACCEPTED"));
        when(outbox.markDelivered(eq(schedule), any(), eq(NOW), any())).thenReturn(true);
        when(outbox.markDelivered(eq(metadata), any(), eq(NOW), any())).thenReturn(true);

        var result = new CalendarOutboxDispatchService(outbox, schedules, metadataClient,
                new CalendarCaptureState(false, true), Clock.fixed(NOW, ZoneOffset.UTC)).dispatchPending();

        assertThat(result.deliveredCount()).isEqualTo(2);
        var order = inOrder(schedules, outbox, metadataClient);
        order.verify(schedules).deliver(schedule);
        order.verify(outbox).markDelivered(schedule, first.leaseToken(), NOW, "APPLIED");
        order.verify(outbox).claimPending(anyInt(), eq(NOW), any(), eq(true));
        order.verify(metadataClient).deliver(metadata);
    }

    @Test
    @DisplayName("응답을 잃은 같은 CAL 아웃박스 행은 재전송 후 중복으로 완료된다")
    void completesSameDeliveryAfterLostResponse() {
        CalendarOutboxPort outboxPort = mock(CalendarOutboxPort.class);
        CalendarSnapshotClient client = mock(CalendarSnapshotClient.class);
        CalendarSnapshot snapshot = snapshot(31);
        CalendarDelivery first = delivery(snapshot, 1);
        CalendarDelivery retry = delivery(snapshot, 2);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any(), eq(false)))
                .thenReturn(List.of(first))
                .thenReturn(List.of(retry));
        when(client.deliver(snapshot))
                .thenReturn(CalendarSnapshotClient.DeliveryResult.retryable("CAL_NETWORK_FAILURE"))
                .thenReturn(CalendarSnapshotClient.DeliveryResult.delivered("DUPLICATE"));
        when(outboxPort.markDelivered(snapshot, retry.leaseToken(), NOW, "DUPLICATE"))
                .thenReturn(true);

        var firstResult = service(outboxPort, client).dispatchPending();
        var retryResult = service(outboxPort, client).dispatchPending();

        assertThat(firstResult.failedCount()).isEqualTo(1);
        assertThat(retryResult.deliveredCount()).isEqualTo(1);
        verify(outboxPort).markRetry(
                snapshot,
                first.leaseToken(),
                NOW.plusSeconds(10),
                "CAL_NETWORK_FAILURE"
        );
        verify(outboxPort).markDelivered(snapshot, retry.leaseToken(), NOW, "DUPLICATE");
        verify(outboxPort, never()).claimPending(anyInt(), any(), any(), eq(true));
    }

    @Test
    @DisplayName("CAL 계약 충돌은 새 개정 번호를 만들지 않고 현재 아웃박스 행을 실패로 끝낸다")
    void failsPermanentContractConflict() {
        CalendarOutboxPort outboxPort = mock(CalendarOutboxPort.class);
        CalendarSnapshotClient client = mock(CalendarSnapshotClient.class);
        CalendarDelivery delivery = delivery(snapshot(41), 1);
        when(outboxPort.claimPending(anyInt(), eq(NOW), any(), eq(false)))
                .thenReturn(List.of(delivery));
        when(client.deliver((CalendarSnapshot) delivery.payload()))
                .thenReturn(CalendarSnapshotClient.DeliveryResult.permanentFailure(
                        "SOURCE_REVISION_CONFLICT"
                ));

        var result = service(outboxPort, client).dispatchPending();

        assertThat(result.failedCount()).isEqualTo(1);
        verify(outboxPort).markFailed(
                delivery.payload(),
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
                mock(CalendarSeasonMetadataClient.class),
                new CalendarCaptureState(true, false),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private CalendarDelivery delivery(CalendarSnapshot snapshot, int attemptCount) {
        return new CalendarDelivery(snapshot, attemptCount, UUID.randomUUID());
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
