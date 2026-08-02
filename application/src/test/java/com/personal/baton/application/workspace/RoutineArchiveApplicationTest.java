package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoundScheduleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoutineCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.application.watch.WatchMonitorChangeRecorder;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository.ScheduledSeasonCandidate;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.RoundRecurrence;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.RoutinePhase;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import com.personal.baton.domain.workspace.Team;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("policy")
class RoutineArchiveApplicationTest {

    private static final String ACCESS_KEY = "routine-archive-access-key";
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @DisplayName("루틴 보관은 최초 시각을 유지하고 보관 중 수정과 복사를 막은 뒤 복원할 수 있다")
    @Test
    void preservesFirstArchiveTimeAndBlocksNormalChangesUntilRestore() {
        Routine routine = routine(UUID.randomUUID(), UUID.randomUUID(), null, null);

        routine.updateArchive(true, NOW);
        routine.updateArchive(true, NOW.plusSeconds(60));

        assertThat(routine.getArchivedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> routine.updateDeadlineRule(0, LocalTime.NOON))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("보관된 루틴");
        assertThatThrownBy(() -> routine.copyToSeason(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        ))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("보관된 루틴");

        routine.updateArchive(false, NOW.plusSeconds(120));
        routine.updateDeadlineRule(0, LocalTime.NOON);

        assertThat(routine.getArchivedAt()).isNull();
        assertThat(routine.getDeadlineTime()).isEqualTo(LocalTime.NOON);
    }

    @DisplayName("수동 회차 snapshot은 보관된 루틴 정의를 제외한다")
    @Test
    void excludesArchivedRoutineFromManualRoundSnapshot() {
        UUID seasonId = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        Routine active = routine(seasonId, UUID.randomUUID(), 0, LocalTime.NOON);
        Routine archived = routine(seasonId, UUID.randomUUID(), 0, LocalTime.NOON);
        archived.updateArchive(true, NOW);

        List<RoutineExecution> executions = new RoutineExecutionSnapshotFactory().snapshotAll(
                roundId,
                List.of(active, archived),
                LocalDate.of(2026, 8, 1),
                SEOUL
        );

        assertThat(executions)
                .singleElement()
                .extracting(RoutineExecution::getRoutineId)
                .isEqualTo(active.getId());
    }

    @DisplayName("루틴 보관은 시즌 배타 잠금을 사용하고 서버 Clock 시각을 결과에 반영한다")
    @Test
    void archivesRoutineWithExclusiveSeasonLockAndServerClock() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        Routine routine = routine(seasonId, UUID.randomUUID(), null, null);
        stubArchiveAuthorization(repository, team, season);
        when(repository.findRoutineById(routine.getId())).thenReturn(Optional.of(routine));
        when(repository.saveRoutine(routine)).thenReturn(routine);

        var result = service(repository).updateRoutineArchive(
                teamId,
                seasonId,
                routine.getId(),
                ACCESS_KEY,
                true
        );

        assertThat(result.archivedAt()).isEqualTo(NOW);
        verify(repository).findSeasonByTeamIdAndIdForUpdate(teamId, seasonId);
        verify(repository, never()).findSeasonByTeamIdAndIdWithSharedLock(teamId, seasonId);
    }

    @DisplayName("활성 자동 일정으로 복원하는 루틴에는 실제 마감 규칙이 필요하다")
    @Test
    void requiresDeadlineRuleWhenRestoringIntoEnabledSchedule() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 1),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7
        );
        Routine routine = routine(seasonId, UUID.randomUUID(), null, null);
        routine.updateArchive(true, NOW.minusSeconds(60));
        stubArchiveAuthorization(repository, team, season);
        when(repository.findRoutineById(routine.getId())).thenReturn(Optional.of(routine));

        assertThatThrownBy(() -> service(repository).updateRoutineArchive(
                teamId,
                seasonId,
                routine.getId(),
                ACCESS_KEY,
                false
        ))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("실제 마감 규칙");
        assertThat(routine.getArchivedAt()).isNotNull();
        verify(repository, never()).saveRoutine(any());
    }

    @DisplayName("보관된 루틴은 일반 수정 대상에서 찾을 수 없는 것으로 처리한다")
    @Test
    void hidesArchivedRoutineFromNormalUpdate() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        Routine routine = routine(seasonId, UUID.randomUUID(), null, null);
        routine.updateArchive(true, NOW);
        when(repository.findTeamByIdWithSharedLock(teamId)).thenReturn(Optional.of(team));
        when(repository.findSeasonByTeamIdAndIdWithSharedLock(teamId, seasonId))
                .thenReturn(Optional.of(season));
        when(repository.findRoutineById(routine.getId())).thenReturn(Optional.of(routine));

        assertThatThrownBy(() -> service(repository).updateRoutine(
                teamId,
                seasonId,
                routine.getId(),
                ACCESS_KEY,
                new UpdateRoutineCommand(
                        "수정하려는 보관 루틴",
                        RoutinePhase.AFTER,
                        "모임 다음 날",
                        routine.getOwnerRoleId(),
                        "보관 상태에서는 바뀌면 안 됩니다"
                )
        ))
                .isInstanceOf(WorkspaceNotFoundException.class)
                .hasMessageContaining("루틴을 찾을 수 없습니다");
        verify(repository, never()).saveRoutine(any());
    }

    @DisplayName("자동 일정 활성화는 보관된 루틴의 마감 규칙을 검사하지 않는다")
    @Test
    void ignoresArchivedRoutineWhenActivatingSchedule() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        Routine active = routine(seasonId, UUID.randomUUID(), -1, LocalTime.of(23, 0));
        Routine archived = routine(seasonId, UUID.randomUUID(), null, null);
        archived.updateArchive(true, NOW);
        stubArchiveAuthorization(repository, team, season);
        when(repository.findSeasonRoundsBySeasonId(seasonId)).thenReturn(List.of());
        when(repository.findRoutinesBySeasonId(seasonId)).thenReturn(List.of(active, archived));
        when(repository.saveSeason(season)).thenReturn(season);

        var result = service(repository).updateRoundSchedule(
                teamId,
                seasonId,
                ACCESS_KEY,
                new UpdateRoundScheduleCommand(
                        "Asia/Seoul",
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(20, 0),
                        RoundRecurrence.WEEKLY,
                        7,
                        true
                )
        );

        assertThat(result.roundSchedule()).isNotNull();
        assertThat(result.roundSchedule().enabled()).isTrue();
    }

    @DisplayName("자동 회차 생성도 보관된 루틴을 검사하거나 snapshot하지 않는다")
    @Test
    void excludesArchivedRoutineFromAutomaticRoundSnapshot() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        UUID teamId = UUID.randomUUID();
        UUID seasonId = UUID.randomUUID();
        Team team = team(teamId);
        Season season = season(teamId, seasonId);
        season.configureRoundSchedule(
                LocalDate.of(2026, 8, 1),
                LocalTime.of(20, 0),
                RoundRecurrence.WEEKLY,
                7
        );
        Routine active = routine(seasonId, UUID.randomUUID(), -1, LocalTime.of(23, 0));
        Routine archived = routine(seasonId, UUID.randomUUID(), null, null);
        archived.updateArchive(true, NOW.minusSeconds(60));
        AtomicReference<List<RoutineExecution>> savedExecutions = new AtomicReference<>();
        when(repository.findTeamByIdWithSharedLock(teamId)).thenReturn(Optional.of(team));
        when(repository.findSeasonByTeamIdAndIdForUpdate(teamId, seasonId))
                .thenReturn(Optional.of(season));
        when(repository.findRoutinesBySeasonId(seasonId)).thenReturn(List.of(active, archived));
        when(repository.existsSeasonRoundBySeasonIdAndScheduledOccurrenceDate(
                seasonId,
                LocalDate.of(2026, 8, 1)
        )).thenReturn(false);
        when(repository.existsSeasonRoundBySeasonIdAndName(
                seasonId,
                "자동 회차 2026-08-01"
        )).thenReturn(false);
        when(repository.saveSeasonRound(any(SeasonRound.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.saveRoutineExecutions(anyList())).thenAnswer(invocation -> {
            List<RoutineExecution> executions = List.copyOf(invocation.getArgument(0));
            savedExecutions.set(executions);
            return executions;
        });
        when(repository.saveSeason(season)).thenReturn(season);

        boolean generated = new ScheduledRoundGenerationWorker(repository)
                .generateNextOccurrence(
                        new ScheduledSeasonCandidate(teamId, seasonId),
                        Instant.parse("2026-07-25T00:00:00Z")
                );

        assertThat(generated).isTrue();
        assertThat(savedExecutions.get())
                .singleElement()
                .extracting(RoutineExecution::getRoutineId)
                .isEqualTo(active.getId());
    }

    private WorkspaceService service(WorkspaceRepository repository) {
        return new WorkspaceService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                WorkspaceSecrets.unconfigured(),
                mock(WatchMonitorChangeRecorder.class)
        );
    }

    private void stubArchiveAuthorization(
            WorkspaceRepository repository,
            Team team,
            Season season
    ) {
        when(repository.findTeamByIdWithSharedLock(team.getId())).thenReturn(Optional.of(team));
        when(repository.findSeasonByTeamIdAndIdForUpdate(team.getId(), season.getId()))
                .thenReturn(Optional.of(season));
    }

    private Team team(UUID teamId) {
        return Team.create(teamId, "루틴 보관 팀", sha256Hex(ACCESS_KEY));
    }

    private Season season(UUID teamId, UUID seasonId) {
        return Season.create(
                seasonId,
                teamId,
                "2026 여름",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31),
                SEOUL.getId()
        );
    }

    private Routine routine(
            UUID seasonId,
            UUID ownerRoleId,
            Integer deadlineDayOffset,
            LocalTime deadlineTime
    ) {
        return Routine.create(
                UUID.randomUUID(),
                seasonId,
                "회고 준비",
                RoutinePhase.BEFORE,
                "모임 전날",
                ownerRoleId,
                "회고 질문에 답한다",
                deadlineDayOffset,
                deadlineTime
        );
    }

    private String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
