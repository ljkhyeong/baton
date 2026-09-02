package com.personal.baton.application.calendar;

import com.personal.baton.application.calendar.port.in.CompleteCalendarRecoveryUseCase.Result.Status;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryClient;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort.RecoveryState;
import com.personal.baton.application.calendar.port.out.CalendarRecoveryStatePort.SeasonState;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient.DeliveryResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CalendarRecoveryCompletionServiceTest {

    private final CalendarRecoveryStatePort statePort = mock(CalendarRecoveryStatePort.class);
    private final CalendarRecoveryClient client = mock(CalendarRecoveryClient.class);
    private final CalendarRecoveryCompletionService service = new CalendarRecoveryCompletionService(statePort, client);

    @Test
    @DisplayName("최신 아웃박스 전달이 끝난 뒤 시즌 검증과 전체 완료를 순서대로 확인한다")
    void completesDeliveredRecoveryState() {
        UUID recoveryId = UUID.randomUUID();
        when(statePort.loadReadyState()).thenReturn(Optional.of(state()));
        when(client.verifySeason(eq(recoveryId), any())).thenReturn(DeliveryResult.delivered("VERIFIED"));
        when(client.complete(eq(recoveryId), any())).thenReturn(DeliveryResult.delivered("COMPLETED"));

        var result = service.complete(recoveryId);

        assertThat(result.status()).isEqualTo(Status.COMPLETED);
        assertThat(result.seasonCount()).isOne();
        verify(client).verifySeason(eq(recoveryId), any());
        verify(client).complete(eq(recoveryId), any());
    }

    @Test
    @DisplayName("최신 아웃박스가 남아 있으면 CAL 매니페스트를 보내지 않는다")
    void waitsForOutboxDelivery() {
        when(statePort.loadReadyState()).thenReturn(Optional.empty());

        var result = service.complete(UUID.randomUUID());

        assertThat(result.status()).isEqualTo(Status.WAITING);
        assertThat(result.code()).isEqualTo("CAL_OUTBOX_NOT_READY");
        verifyNoInteractions(client);
    }

    private RecoveryState state() {
        UUID seasonId = UUID.fromString("30000000-0000-0000-0000-000000000001");
        Instant occurredAt = Instant.parse("2026-09-02T03:00:00Z");
        var snapshot = new CalendarSnapshot(
                UUID.fromString("10000000-0000-0000-0000-000000000001"),
                occurredAt,
                UUID.fromString("20000000-0000-0000-0000-000000000001"),
                seasonId,
                7,
                CalendarSnapshot.Status.CANCELLED,
                "복구 일정",
                null,
                null,
                new CalendarSnapshot.AllDay(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3)),
                occurredAt
        );
        return new RecoveryState(List.of(new SeasonState(
                seasonId,
                List.of(snapshot),
                new CalendarSeasonMetadata(seasonId, 3, "복구 시즌")
        )));
    }
}
