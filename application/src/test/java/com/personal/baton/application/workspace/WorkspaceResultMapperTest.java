package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoutineExecutionResult;
import com.personal.baton.domain.workspace.RoundTimingStatus;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineTimingStatus;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("policy")
class WorkspaceResultMapperTest {

    private static final Instant BEFORE_DEADLINE = Instant.parse("2026-08-01T11:59:59Z");
    private static final Instant AFTER_DEADLINE = Instant.parse("2026-08-01T12:00:01Z");
    private static final ZoneId SEASON_ZONE = ZoneId.of("Asia/Seoul");

    @DisplayName("회차 변경 응답은 한 번 고정한 시각으로 모든 실행 상태를 계산한다")
    @Test
    void usesOneTimeSnapshotForSeasonRoundMutationResult() {
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(BEFORE_DEADLINE, AFTER_DEADLINE);
        when(clock.getZone()).thenReturn(SEASON_ZONE);
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Season season = Season.create(
                seasonId,
                teamId,
                "2026 여름",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31),
                SEASON_ZONE.getId()
        );
        SeasonRound round = SeasonRound.create(
                UUID.randomUUID(),
                seasonId,
                "여름 두 번째 모임",
                LocalDate.of(2026, 8, 1)
        );
        List<RoutineExecution> executions = List.of(
                execution(seasonId, round.getId(), "발표 자료 확인"),
                execution(seasonId, round.getId(), "질문 목록 확인")
        );

        var result = new WorkspaceResultMapper(clock)
                .toSeasonRoundResult(round, executions, season);

        assertThat(result.routineExecutions())
                .extracting(RoutineExecutionResult::timingStatus)
                .containsExactly(
                        RoutineTimingStatus.IN_PROGRESS,
                        RoutineTimingStatus.IN_PROGRESS
                );
        assertThat(result.timingStatus()).isEqualTo(RoundTimingStatus.IN_PROGRESS);
        verify(clock, times(1)).instant();
    }

    private RoutineExecution execution(UUID seasonId, UUID roundId, String title) {
        Routine routine = Routine.create(
                UUID.randomUUID(),
                seasonId,
                title,
                RoutinePhase.BEFORE,
                "모임 시작 전",
                UUID.randomUUID(),
                "준비 상태를 확인한다",
                0,
                LocalTime.of(21, 0)
        );
        return RoutineExecution.snapshot(
                UUID.randomUUID(),
                roundId,
                routine,
                LocalDate.of(2026, 8, 1),
                SEASON_ZONE
        );
    }
}
