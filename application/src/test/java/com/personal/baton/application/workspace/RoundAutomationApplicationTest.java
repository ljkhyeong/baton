package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceContract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.personal.baton.application.calendar.CalendarChangeRecorder;
import com.personal.baton.application.crypto.DomainSeparatedSha256;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.CreateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceLifecycleCommands.UpdateRoundScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceOperationsCommands.UpdateSeasonRoundCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.application.watch.WatchMonitorChangeRecorder;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository.ScheduledSeasonCandidate;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoundRecurrence;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import com.personal.baton.domain.workspace.Team;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("usecase")
class RoundAutomationApplicationTest {

    private static final String ACCESS_KEY = "round-automation-access-key";
    private static final Instant NOW = Instant.parse("2026-07-25T00:00:00Z");

    private final WorkspaceAccessRepository accessRepository = mock(WorkspaceAccessRepository.class);
    private final WorkspaceSeasonRepository seasonRepository = mock(WorkspaceSeasonRepository.class);
    private final WorkspacePeopleRepository peopleRepository = mock(WorkspacePeopleRepository.class);
    private final WorkspaceOperationsRepository operationsRepository = mock(WorkspaceOperationsRepository.class);
    private final WorkspaceRecordsRepository recordsRepository = mock(WorkspaceRecordsRepository.class);
    private final BriefContinuitySignalRecorder briefRecorder = mock(BriefContinuitySignalRecorder.class);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("실제 마감 규칙이 없는 루틴이 있으면 자동 회차 일정을 처음 활성화하거나 재개할 수 없다")
    void rejectsScheduleActivationWhenRoutineHasNoDeadlineRule(boolean resuming) {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        if (resuming) {
            season.configureRoundSchedule(
                    LocalDate.of(2026, 8, 1), LocalTime.of(20, 0), RoundRecurrence.WEEKLY, 7, false
            );
        }
        Routine routine = routine(seasonId, null, null);
        stubScheduleAuthorization(team, season);
        when(operationsRepository.findRoutinesBySeasonId(seasonId)).thenReturn(List.of(routine));

        WorkspaceServiceTestFactory.Services service = workspaceService();

        assertThatThrownBy(() -> service.lifecycle().updateRoundSchedule(
                teamId,
                seasonId,
                ACCESS_KEY,
                scheduleCommand(true)
        ))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("모든 루틴에 실제 마감 규칙");
        verify(seasonRepository, never()).saveSeason(any());
    }

    @Test
    @DisplayName("자동 회차 일정은 시즌 시간대와 다음 발생 커서를 함께 반환한다")
    void updatesRoundSchedule() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        stubScheduleAuthorization(team, season);
        when(operationsRepository.findRoutinesBySeasonId(seasonId)).thenReturn(List.of(
                routine(seasonId, -1, LocalTime.of(23, 0))
        ));
        when(seasonRepository.saveSeason(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkspaceServiceTestFactory.Services service = workspaceService();
        var result = service.lifecycle().updateRoundSchedule(
                teamId,
                seasonId,
                ACCESS_KEY,
                scheduleCommand(true)
        );

        assertThat(result.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(result.roundSchedule()).isNotNull();
        assertThat(result.roundSchedule().enabled()).isTrue();
        assertThat(result.roundSchedule().nextOccurrenceDate())
                .isEqualTo(LocalDate.of(2026, 8, 1));
    }

    @Test
    @DisplayName("자동 회차 일정을 일시 중지하고 재개해도 다음 발생 커서를 되감지 않는다")
    void pausesAndResumesScheduleWithoutRewindingCursor() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 1),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7,
                true
        );
        season.advanceRoundSchedule();
        stubScheduleAuthorization(team, season);
        when(operationsRepository.findRoutinesBySeasonId(seasonId)).thenReturn(List.of(
                routine(seasonId, -1, LocalTime.of(23, 0))
        ));
        when(seasonRepository.saveSeason(any())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkspaceServiceTestFactory.Services service = workspaceService();

        var paused = service.lifecycle().updateRoundSchedule(
                teamId,
                seasonId,
                ACCESS_KEY,
                scheduleCommand(false)
        );
        var resumed = service.lifecycle().updateRoundSchedule(
                teamId,
                seasonId,
                ACCESS_KEY,
                scheduleCommand(true)
        );

        assertThat(paused.roundSchedule().enabled()).isFalse();
        assertThat(paused.roundSchedule().nextOccurrenceDate())
                .isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(resumed.roundSchedule().enabled()).isTrue();
        assertThat(resumed.roundSchedule().nextOccurrenceDate())
                .isEqualTo(LocalDate.of(2026, 8, 8));
        verify(operationsRepository).findRoutinesBySeasonId(seasonId);
    }

    @Test
    @DisplayName("활성 상태를 유지하는 자동 회차 설정 변경은 마감 재검사와 BRIEF 재계산을 생략한다")
    void updatesEnabledScheduleWithoutRecheckingDeadlineRulesOrBriefSignals() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Season season = season(teamId, seasonId);
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 1), LocalTime.of(20, 0), RoundRecurrence.WEEKLY, 7, true
        );
        stubScheduleAuthorization(team(teamId), season);
        when(seasonRepository.saveSeason(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = workspaceService().lifecycle().updateRoundSchedule(
                teamId, seasonId, ACCESS_KEY,
                new UpdateRoundScheduleCommand(
                        "Asia/Seoul", LocalDate.of(2026, 8, 1), LocalTime.of(21, 0),
                        RoundRecurrence.BIWEEKLY, 14, true
                )
        );

        assertThat(result.roundSchedule().meetingTime()).isEqualTo(LocalTime.of(21, 0));
        assertThat(result.roundSchedule().recurrence()).isEqualTo(RoundRecurrence.BIWEEKLY);
        assertThat(result.roundSchedule().generationLeadDays()).isEqualTo(14);
        verify(operationsRepository, never()).findRoutinesBySeasonId(any());
        verifyNoInteractions(briefRecorder);
    }

    @Test
    @DisplayName("자동 회차 설정에서 시즌 시간대를 바꾸면 BRIEF 신호를 다시 계산한다")
    void reconcilesBriefSignalsWhenTimeZoneChanges() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Season season = season(teamId, seasonId);
        stubScheduleAuthorization(team(teamId), season);
        when(seasonRepository.saveSeason(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = workspaceService().lifecycle().updateRoundSchedule(
                teamId, seasonId, ACCESS_KEY,
                new UpdateRoundScheduleCommand(
                        "America/New_York", LocalDate.of(2026, 8, 1), LocalTime.of(20, 0),
                        RoundRecurrence.WEEKLY, 7, false
                )
        );

        assertThat(result.timeZone()).isEqualTo("America/New_York");
        verify(briefRecorder).reconcileSeason(teamId, seasonId);
    }

    @Test
    @DisplayName("기존 회차가 있으면 시즌 시간대를 바꿀 수 없다")
    void rejectsTimeZoneChangeAfterRoundCreation() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        stubScheduleAuthorization(team, season);
        when(operationsRepository.existsSeasonRoundBySeasonId(seasonId)).thenReturn(true);

        WorkspaceServiceTestFactory.Services service = workspaceService();
        UpdateRoundScheduleCommand command = new UpdateRoundScheduleCommand(
                "America/New_York",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7,
                false
        );

        assertThatThrownBy(() ->
                service.lifecycle().updateRoundSchedule(teamId, seasonId, ACCESS_KEY, command))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("시간대를 변경할 수 없습니다");
        verify(operationsRepository, never()).findSeasonRoundsBySeasonId(any());
    }

    @Test
    @DisplayName("정규화 결과가 같은 시간대는 회차 조회 없이 일정 설정에 사용할 수 있다")
    void acceptsEquivalentTimeZoneWithoutLoadingRounds() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        stubScheduleAuthorization(team, season);
        when(seasonRepository.saveSeason(any())).thenAnswer(invocation -> invocation.getArgument(0));

        WorkspaceServiceTestFactory.Services service = workspaceService();
        UpdateRoundScheduleCommand command = new UpdateRoundScheduleCommand(
                " Asia/Seoul ",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7,
                false
        );

        var result = service.lifecycle().updateRoundSchedule(teamId, seasonId, ACCESS_KEY, command);

        assertThat(result.timeZone()).isEqualTo("Asia/Seoul");
        assertThat(result.roundSchedule()).isNotNull();
        verify(operationsRepository, never()).existsSeasonRoundBySeasonId(any());
        verify(operationsRepository, never()).findSeasonRoundsBySeasonId(any());
        verifyNoInteractions(briefRecorder);
    }

    @Test
    @DisplayName("수동 회차를 옮기면 루틴 실행의 실제 마감 시각도 다시 계산한다")
    void recalculatesManualRoundDeadlinesWhenMeetingDateMoves() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        Routine routine = routine(seasonId, -1, LocalTime.of(23, 0));
        AtomicReference<SeasonRound> savedRound = new AtomicReference<>();
        AtomicReference<List<RoutineExecution>> savedExecutions = new AtomicReference<>();
        when(accessRepository.findTeamByIdWithSharedLock(teamId)).thenReturn(Optional.of(team));
        when(seasonRepository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId))
                .thenReturn(Optional.of(season));
        when(accessRepository.findContentCreationIdempotency(any(), any())).thenReturn(Optional.empty());
        when(operationsRepository.findRoutinesBySeasonId(seasonId)).thenReturn(List.of(routine));
        when(operationsRepository.saveSeasonRound(any())).thenAnswer(invocation -> {
            SeasonRound round = invocation.getArgument(0);
            savedRound.set(round);
            return round;
        });
        when(operationsRepository.saveRoutineExecutions(any())).thenAnswer(invocation -> {
            List<RoutineExecution> executions = invocation.getArgument(0);
            savedExecutions.set(executions);
            return executions;
        });

        WorkspaceServiceTestFactory.Services service = workspaceService();
        var created = service.operations().createSeasonRound(
                teamId,
                seasonId,
                "manual-round-idempotency-key-000001",
                ACCESS_KEY,
                new CreateSeasonRoundCommand("첫 회차", LocalDate.of(2026, 8, 1))
        );
        when(operationsRepository.findSeasonRoundBySeasonIdAndIdForUpdate(seasonId, created.id()))
                .thenReturn(Optional.of(savedRound.get()));
        when(operationsRepository.findRoutineExecutionsBySeasonRoundIds(List.of(created.id())))
                .thenReturn(savedExecutions.get());

        var moved = service.operations().updateSeasonRound(
                teamId,
                seasonId,
                created.id(),
                ACCESS_KEY,
                new UpdateSeasonRoundCommand("둘째 회차", LocalDate.of(2026, 8, 8))
        );

        assertThat(created.routineExecutions()).singleElement()
                .extracting(execution -> execution.deadlineAt())
                .isEqualTo(Instant.parse("2026-07-31T14:00:00Z"));
        assertThat(moved.routineExecutions()).singleElement()
                .extracting(execution -> execution.deadlineAt())
                .isEqualTo(Instant.parse("2026-08-07T14:00:00Z"));
    }

    @Test
    @DisplayName("자동 생성기는 예정일과 시간대로 회차와 실제 마감 스냅샷을 만든다")
    void createsAutomaticRoundAndDeadlineSnapshot() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 1),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7,
                true
        );
        Routine routine = routine(seasonId, -1, LocalTime.of(23, 0));
        AtomicReference<SeasonRound> savedRound = new AtomicReference<>();
        AtomicReference<List<RoutineExecution>> savedExecutions = new AtomicReference<>();

        when(accessRepository.findTeamByIdWithSharedLock(teamId)).thenReturn(Optional.of(team));
        when(seasonRepository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId))
                .thenReturn(Optional.of(season));
        when(operationsRepository.existsSeasonRoundBySeasonIdAndScheduledOccurrenceDate(
                seasonId,
                LocalDate.of(2026, 8, 1)
        )).thenReturn(false);
        when(operationsRepository.existsSeasonRoundBySeasonIdAndName(seasonId, "자동 회차 2026-08-01"))
                .thenReturn(false);
        when(operationsRepository.findRoutinesBySeasonId(seasonId)).thenReturn(List.of(routine));
        when(operationsRepository.saveSeasonRound(any())).thenAnswer(invocation -> {
            SeasonRound round = invocation.getArgument(0);
            savedRound.set(round);
            return round;
        });
        when(operationsRepository.saveRoutineExecutions(any())).thenAnswer(invocation -> {
            List<RoutineExecution> executions = invocation.getArgument(0);
            savedExecutions.set(executions);
            return executions;
        });
        when(seasonRepository.saveSeason(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BriefContinuitySignalRecorder briefRecorder = mock(BriefContinuitySignalRecorder.class);
        CalendarChangeRecorder calendarRecorder = mock(CalendarChangeRecorder.class);
        ScheduledRoundGenerationWorker worker = new ScheduledRoundGenerationWorker(
                accessRepository,
                seasonRepository,
                operationsRepository,
                new RoutineExecutionSnapshotFactory(),
                briefRecorder,
                calendarRecorder
        );
        boolean processed = worker.generateNextOccurrence(
                new ScheduledSeasonCandidate(teamId, seasonId),
                NOW
        );

        assertThat(processed).isTrue();
        verify(briefRecorder).reconcileSeason(teamId, seasonId);
        assertThat(savedRound.get().getScheduledAt())
                .isEqualTo(Instant.parse("2026-08-01T11:00:00Z"));
        assertThat(savedExecutions.get()).singleElement()
                .extracting(RoutineExecution::getDeadlineAt)
                .isEqualTo(Instant.parse("2026-07-31T14:00:00Z"));
        assertThat(season.getRoundSchedule().getNextOccurrenceDate())
                .isEqualTo(LocalDate.of(2026, 8, 8));
        verify(calendarRecorder).record(season, savedRound.get(), savedExecutions.get());
    }

    @Test
    @DisplayName("이미 생성된 예정일은 회차를 중복 저장하지 않고 커서만 전진한다")
    void skipsExistingAutomaticRoundOccurrence() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 1),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7,
                true
        );
        when(accessRepository.findTeamByIdWithSharedLock(teamId)).thenReturn(Optional.of(team));
        when(seasonRepository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId))
                .thenReturn(Optional.of(season));
        when(operationsRepository.existsSeasonRoundBySeasonIdAndScheduledOccurrenceDate(
                seasonId,
                LocalDate.of(2026, 8, 1)
        )).thenReturn(true);
        when(seasonRepository.saveSeason(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ScheduledRoundGenerationWorker worker = new ScheduledRoundGenerationWorker(
                accessRepository,
                seasonRepository,
                operationsRepository,
                new RoutineExecutionSnapshotFactory(),
                briefRecorder,
                mock(CalendarChangeRecorder.class)
        );
        boolean processed = worker.generateNextOccurrence(
                new ScheduledSeasonCandidate(teamId, seasonId),
                NOW
        );

        assertThat(processed).isTrue();
        verify(operationsRepository, never()).saveSeasonRound(any());
        verify(operationsRepository, never()).saveRoutineExecutions(any());
        verifyNoInteractions(briefRecorder);
        assertThat(season.getRoundSchedule().getNextOccurrenceDate())
                .isEqualTo(LocalDate.of(2026, 8, 8));
    }

    @Test
    @DisplayName("활성 루틴이 없으면 빈 자동 회차를 저장하지 않고 발생 커서만 전진한다")
    void skipsAutomaticRoundWithoutActiveRoutinesAndAdvancesOccurrence() {
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 1),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7,
                true
        );
        Routine archived = routine(seasonId, -1, LocalTime.of(23, 0));
        archived.updateArchive(true, NOW);
        when(accessRepository.findTeamByIdWithSharedLock(teamId)).thenReturn(Optional.of(team));
        when(seasonRepository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId))
                .thenReturn(Optional.of(season));
        when(operationsRepository.existsSeasonRoundBySeasonIdAndScheduledOccurrenceDate(
                seasonId,
                LocalDate.of(2026, 8, 1)
        )).thenReturn(false);
        when(operationsRepository.findRoutinesBySeasonId(seasonId)).thenReturn(List.of(archived));
        when(seasonRepository.saveSeason(any())).thenAnswer(invocation -> invocation.getArgument(0));

        boolean processed = new ScheduledRoundGenerationWorker(
                accessRepository,
                seasonRepository,
                operationsRepository,
                new RoutineExecutionSnapshotFactory(),
                briefRecorder,
                mock(CalendarChangeRecorder.class)
        ).generateNextOccurrence(
                new ScheduledSeasonCandidate(teamId, seasonId),
                NOW
        );

        assertThat(processed).isTrue();
        verify(operationsRepository, never()).saveSeasonRound(any());
        verify(operationsRepository, never()).saveRoutineExecutions(any());
        verify(seasonRepository).saveSeason(season);
        verifyNoInteractions(briefRecorder);
        assertThat(season.getRoundSchedule().getNextOccurrenceDate())
                .isEqualTo(LocalDate.of(2026, 8, 8));
    }

    @Test
    @DisplayName("한 시즌의 자동 생성 실패가 다음 시즌 처리를 막지 않는다")
    void isolatesGenerationFailureBySeason() {
        ScheduledRoundGenerationWorker worker = mock(ScheduledRoundGenerationWorker.class);
        ScheduledSeasonCandidate failed = new ScheduledSeasonCandidate(
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        ScheduledSeasonCandidate succeeding = new ScheduledSeasonCandidate(
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        when(seasonRepository.findScheduledSeasonCandidates())
                .thenReturn(List.of(failed, succeeding));
        when(worker.generateNextOccurrence(failed, NOW))
                .thenThrow(new IllegalStateException("의도한 실패"));
        when(worker.generateNextOccurrence(succeeding, NOW)).thenReturn(false);

        ScheduledRoundGenerationService service = new ScheduledRoundGenerationService(
                seasonRepository,
                worker,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        var result = service.generateDueRounds();

        verify(worker).generateNextOccurrence(failed, NOW);
        verify(worker).generateNextOccurrence(succeeding, NOW);
        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.failedSeasonIds()).containsExactly(failed.seasonId());
    }

    @Test
    @DisplayName("한 번의 실행에서 시즌별 발생 처리는 여덟 건으로 제한한다")
    void capsOccurrencesPerSeasonPerTick() {
        ScheduledRoundGenerationWorker worker = mock(ScheduledRoundGenerationWorker.class);
        ScheduledSeasonCandidate candidate = new ScheduledSeasonCandidate(
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        when(seasonRepository.findScheduledSeasonCandidates()).thenReturn(List.of(candidate));
        when(worker.generateNextOccurrence(candidate, NOW)).thenReturn(true);

        ScheduledRoundGenerationService service = new ScheduledRoundGenerationService(
                seasonRepository,
                worker,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        service.generateDueRounds();

        verify(worker, times(8)).generateNextOccurrence(candidate, NOW);
    }

    @Test
    @DisplayName("활성 일정이 백 개를 넘어도 뒤쪽 시즌을 같은 실행에서 처리한다")
    void processesScheduledSeasonsBeyondFirstHundred() {
        ScheduledRoundGenerationWorker worker = mock(ScheduledRoundGenerationWorker.class);
        List<ScheduledSeasonCandidate> candidates = IntStream.range(0, 101)
                .mapToObj(index -> new ScheduledSeasonCandidate(
                        UUID.randomUUID(),
                        UUID.randomUUID()
                ))
                .toList();
        ScheduledSeasonCandidate lastCandidate = candidates.getLast();
        when(seasonRepository.findScheduledSeasonCandidates()).thenReturn(candidates);
        when(worker.generateNextOccurrence(any(), any())).thenReturn(false);

        ScheduledRoundGenerationService service = new ScheduledRoundGenerationService(
                seasonRepository,
                worker,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        service.generateDueRounds();

        verify(worker).generateNextOccurrence(lastCandidate, NOW);
    }

    private WorkspaceServiceTestFactory.Services workspaceService() {
        return WorkspaceServiceTestFactory.create(
                accessRepository,
                seasonRepository,
                peopleRepository,
                operationsRepository,
                recordsRepository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspaceSecrets("", ""),
                mock(WatchMonitorChangeRecorder.class),
                briefRecorder,
                mock(CalendarChangeRecorder.class)
        );
    }

    private void stubScheduleAuthorization(
            Team team,
            Season season
    ) {
        when(accessRepository.findTeamByIdWithSharedLock(team.getId())).thenReturn(Optional.of(team));
        when(seasonRepository.findSeasonByTeamIdAndIdForUpdate(team.getId(), season.getId()))
                .thenReturn(Optional.of(season));
    }

    private Team team(UUID teamId) {
        return Team.create(
                teamId,
                "자동화 팀",
                DomainSeparatedSha256.hashUtf8Hex(ACCESS_KEY)
        );
    }

    private Season season(UUID teamId, UUID seasonId) {
        return Season.create(
                seasonId,
                teamId,
                "2026 하반기",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 12, 31)
        );
    }

    private Routine routine(
            UUID seasonId,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        return Routine.create(
                UUID.randomUUID(),
                seasonId,
                "회고 준비",
                RoutinePhase.BEFORE,
                "모임 전날",
                UUID.randomUUID(),
                "회고 질문에 답한다",
                deadlineDayOffset,
                deadlineTime
        );
    }

    private UpdateRoundScheduleCommand scheduleCommand(boolean enabled) {
        return new UpdateRoundScheduleCommand(
                "Asia/Seoul",
                LocalDate.of(2026, 8, 1),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7,
                enabled
        );
    }
}
