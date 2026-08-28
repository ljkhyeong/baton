package com.personal.baton.application.workspace;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceRoundCalendarRecordingTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-25T03:00:00Z"),
            ZoneOffset.UTC
    );

    @DisplayName("수동 회차를 저장한 뒤 회차와 실행의 CAL 변경을 기록한다")
    @Test
    void recordsCreatedRound() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        CalendarChangeRecorder recorder = mock(CalendarChangeRecorder.class);
        Season season = season();
        Routine routine = routine(season.getId());
        when(repository.findRoutinesBySeasonId(season.getId())).thenReturn(List.of(routine));
        when(repository.saveSeasonRound(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveRoutineExecutions(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkspaceRoundCoordinator coordinator = coordinator(repository, recorder);

        coordinator.create(
                UUID.randomUUID(),
                season,
                "calendar-round-create-idempotency-0001",
                new CreateSeasonRoundCommand("8월 회차", LocalDate.of(2026, 8, 25))
        );

        verify(recorder).record(
                eq(season),
                any(SeasonRound.class),
                argThat(executions -> executions.size() == 1)
        );
    }

    @DisplayName("회차 보관 상태를 저장한 뒤 같은 실행 목록으로 CAL 취소 변경을 기록한다")
    @Test
    void recordsArchivedRound() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        CalendarChangeRecorder recorder = mock(CalendarChangeRecorder.class);
        WorkspaceSeasonRoundResolver resolver = mock(WorkspaceSeasonRoundResolver.class);
        Season season = season();
        SeasonRound round = SeasonRound.create(
                UUID.randomUUID(),
                season.getId(),
                "8월 회차",
                LocalDate.of(2026, 8, 25)
        );
        RoutineExecution execution = RoutineExecution.snapshot(
                UUID.randomUUID(),
                round.getId(),
                routine(season.getId()),
                round.getMeetingDate(),
                season.getZoneId()
        );
        when(resolver.requireForUpdate(season.getId(), round.getId())).thenReturn(round);
        when(repository.saveSeasonRound(round)).thenReturn(round);
        when(repository.findRoutineExecutionsBySeasonRoundIdWithSharedLock(round.getId()))
                .thenReturn(List.of(execution));
        WorkspaceRoundCoordinator coordinator = coordinator(repository, recorder, resolver);

        coordinator.updateArchive(season, round.getId(), true);

        verify(recorder).record(season, round, List.of(execution));
    }

    private WorkspaceRoundCoordinator coordinator(
            WorkspaceRepository repository,
            CalendarChangeRecorder recorder
    ) {
        return coordinator(repository, recorder, mock(WorkspaceSeasonRoundResolver.class));
    }

    private WorkspaceRoundCoordinator coordinator(
            WorkspaceRepository repository,
            CalendarChangeRecorder recorder,
            WorkspaceSeasonRoundResolver resolver
    ) {
        return new WorkspaceRoundCoordinator(
                repository,
                CLOCK,
                new WorkspaceContentIdempotency(repository),
                new WorkspaceResultMapper(CLOCK),
                resolver,
                new RoutineExecutionSnapshotFactory(),
                recorder
        );
    }

    private Season season() {
        return Season.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "캘린더 시즌",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31)
        );
    }

    private Routine routine(UUID seasonId) {
        return Routine.create(
                UUID.randomUUID(),
                seasonId,
                "준비 마감",
                RoutinePhase.BEFORE,
                "전날",
                UUID.randomUUID(),
                "준비 내용을 확인합니다",
                -1,
                LocalTime.of(20, 0)
        );
    }
}
