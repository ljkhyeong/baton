package com.personal.baton.bootstrap.scheduling;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.personal.baton.application.workspace.port.in.ScheduledRoundGenerationUseCase;
import com.personal.baton.application.workspace.port.in.ScheduledRoundGenerationUseCase.GenerationResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("usecase")
class RoundAutomationSchedulerTest {

    @Test
    @DisplayName("자동 회차 생성이 모두 성공하면 스케줄 실행도 성공으로 끝난다")
    void completesWhenEveryCandidateSucceeds() {
        ScheduledRoundGenerationUseCase useCase = mock(ScheduledRoundGenerationUseCase.class);
        when(useCase.generateDueRounds()).thenReturn(new GenerationResult(3, List.of()));
        RoundAutomationScheduler scheduler = new RoundAutomationScheduler(useCase);

        assertThatCode(scheduler::generateDueRounds).doesNotThrowAnyException();

        verify(useCase).generateDueRounds();
    }

    @Test
    @DisplayName("일부 시즌의 자동 생성이 실패하면 스케줄 실행을 실패로 표시한다")
    void failsScheduledExecutionWhenAnyCandidateFails() {
        ScheduledRoundGenerationUseCase useCase = mock(ScheduledRoundGenerationUseCase.class);
        when(useCase.generateDueRounds()).thenReturn(new GenerationResult(
                3,
                List.of(UUID.randomUUID())
        ));
        RoundAutomationScheduler scheduler = new RoundAutomationScheduler(useCase);

        assertThatThrownBy(scheduler::generateDueRounds)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("자동 회차 생성에 실패한 시즌이 있습니다. failed=1, candidates=3");
    }
}
