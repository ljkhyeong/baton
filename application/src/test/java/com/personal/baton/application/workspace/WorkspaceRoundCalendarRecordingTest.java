package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceContract;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.UpdateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.RoutineStatus;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkspaceRoundCalendarRecordingTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-25T03:00:00Z"),
            ZoneOffset.UTC
    );

    private final BriefContinuitySignalRecorder briefRecorder = mock(BriefContinuitySignalRecorder.class);

    @DisplayName("수동 회차를 저장한 뒤 회차와 실행의 CAL 변경을 기록한다")
    @Test
    void recordsCreatedRound() {
        WorkspaceOperationsRepository repository = mock(WorkspaceOperationsRepository.class);
        CalendarChangeRecorder recorder = mock(CalendarChangeRecorder.class);
        Season season = season();
        Routine routine = routine(season.getId());
        when(repository.findActiveRoutinesBySeasonId(season.getId())).thenReturn(List.of(routine));
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
        WorkspaceOperationsRepository repository = mock(WorkspaceOperationsRepository.class);
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

    @DisplayName("수동·자동 회차는 날짜 변경 때만 실행 마감과 BRIEF를 갱신하고 변경 결과를 CAL에 기록한다")
    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void reschedulesOnlyWhenMeetingDateChanges(boolean automatic, boolean dateChanged) {
        WorkspaceOperationsRepository repository = mock(WorkspaceOperationsRepository.class);
        CalendarChangeRecorder recorder = mock(CalendarChangeRecorder.class);
        WorkspaceSeasonRoundResolver resolver = mock(WorkspaceSeasonRoundResolver.class);
        Season season = season();
        SeasonRound round = automatic
                ? SeasonRound.createAutomatic(UUID.randomUUID(), season.getId(), "8월 회차",
                        LocalDate.of(2026, 8, 25), Instant.parse("2026-08-25T10:00:00Z"))
                : SeasonRound.create(UUID.randomUUID(), season.getId(), "8월 회차", LocalDate.of(2026, 8, 25));
        RoutineExecution execution = RoutineExecution.snapshot(
                UUID.randomUUID(), round.getId(), routine(season.getId()),
                round.getMeetingDate(), season.getZoneId());
        execution.updateCompletion(true);
        List<RoutineExecution> executions = List.of(execution);
        when(resolver.requireActiveForUpdate(season.getId(), round.getId())).thenReturn(round);
        when(repository.saveSeasonRound(round)).thenReturn(round);
        when(repository.findRoutineExecutionsBySeasonRoundIds(List.of(round.getId()))).thenReturn(executions);
        if (dateChanged) {
            when(repository.saveRoutineExecutions(executions)).thenReturn(executions);
        }
        LocalDate meetingDate = dateChanged ? LocalDate.of(2026, 8, 27) : round.getMeetingDate();

        var result = coordinator(repository, recorder, resolver).update(
                season, round.getId(), new UpdateSeasonRoundCommand("정정한 회차", meetingDate));

        assertThat(result.name()).isEqualTo("정정한 회차");
        assertThat(result.meetingDate()).isEqualTo(meetingDate);
        if (automatic) {
            assertThat(result.scheduledOccurrenceDate()).isEqualTo(LocalDate.of(2026, 8, 25));
            assertThat(result.scheduledAt()).isEqualTo(Instant.parse(
                    dateChanged ? "2026-08-27T10:00:00Z" : "2026-08-25T10:00:00Z"));
        }
        assertThat(result.routineExecutions()).singleElement().satisfies(saved -> {
            assertThat(saved.deadlineAt()).isEqualTo(Instant.parse(
                    dateChanged ? "2026-08-26T11:00:00Z" : "2026-08-24T11:00:00Z"));
            assertThat(saved.status()).isEqualTo(RoutineStatus.DONE);
        });
        verify(recorder).record(season, round, executions);
        if (dateChanged) {
            verify(repository).saveRoutineExecutions(executions);
            verify(briefRecorder).reconcileSeason(season.getTeamId(), season.getId());
        } else {
            verify(repository, never()).saveRoutineExecutions(any());
            verifyNoInteractions(briefRecorder);
        }
    }

    private WorkspaceRoundCoordinator coordinator(
            WorkspaceOperationsRepository repository,
            CalendarChangeRecorder recorder
    ) {
        return coordinator(repository, recorder, mock(WorkspaceSeasonRoundResolver.class));
    }

    private WorkspaceRoundCoordinator coordinator(
            WorkspaceOperationsRepository repository,
            CalendarChangeRecorder recorder,
            WorkspaceSeasonRoundResolver resolver
    ) {
        return new WorkspaceRoundCoordinator(
                repository,
                CLOCK,
                new WorkspaceContentIdempotency(mock(WorkspaceAccessRepository.class)),
                new WorkspaceResultMapper(CLOCK),
                resolver,
                new RoutineExecutionSnapshotFactory(),
                recorder,
                briefRecorder
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
