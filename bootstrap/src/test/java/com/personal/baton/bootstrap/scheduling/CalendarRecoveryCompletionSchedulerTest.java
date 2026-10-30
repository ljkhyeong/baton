package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.calendar.port.in.CompleteCalendarRecoveryUseCase;
import com.personal.baton.application.calendar.port.in.CompleteCalendarRecoveryUseCase.Result;
import com.personal.baton.application.calendar.port.in.MaintainCalendarSeasonMetadataUseCase.Mode;
import com.personal.baton.bootstrap.config.CalendarIntegrationProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class CalendarRecoveryCompletionSchedulerTest {

    private static final UUID RECOVERY_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private final CompleteCalendarRecoveryUseCase recovery = mock(CompleteCalendarRecoveryUseCase.class);
    private final CalendarRecoveryCompletionScheduler scheduler = new CalendarRecoveryCompletionScheduler(
            recovery,
            new CalendarIntegrationProperties(
                    true, false, true, true, Mode.OFF, false, RECOVERY_ID.toString(), "", "",
                    Duration.ofSeconds(2), Duration.ofSeconds(5)
            )
    );

    @Test
    @DisplayName("복구 대기 사유는 코드가 바뀔 때만 경고로 남겨 불일치 구분을 잃지 않는다")
    void warnsOnlyWhenWaitingCodeChanges(CapturedOutput output) {
        when(recovery.complete(RECOVERY_ID)).thenReturn(
                Result.waiting("CAL_OUTBOX_NOT_READY"),
                Result.waiting("CAL_OUTBOX_NOT_READY"),
                Result.waiting("CAL_RECOVERY_ITEMS_MISMATCH"),
                Result.waiting("CAL_RECOVERY_ITEMS_MISMATCH"),
                Result.waiting("CAL_OUTBOX_NOT_READY")
        );

        for (int run = 0; run < 5; run++) {
            scheduler.completeRecovery();
        }

        assertThat(output.getOut()).containsSubsequence(
                "code=CAL_OUTBOX_NOT_READY",
                "code=CAL_RECOVERY_ITEMS_MISMATCH",
                "code=CAL_OUTBOX_NOT_READY"
        );
        assertThat(output.getOut().split("code=CAL_", -1)).hasSize(4);
        verify(recovery, times(5)).complete(RECOVERY_ID);
    }

    @Test
    @DisplayName("복구가 끝나면 다음 주기부터 완료 확인을 다시 요청하지 않는다")
    void stopsAfterCompletion() {
        when(recovery.complete(RECOVERY_ID)).thenReturn(Result.completed(2));

        scheduler.completeRecovery();
        scheduler.completeRecovery();

        verify(recovery).complete(RECOVERY_ID);
    }
}
