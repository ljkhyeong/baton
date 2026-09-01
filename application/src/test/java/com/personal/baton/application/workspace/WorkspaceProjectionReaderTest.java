package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceContract;

import com.personal.baton.application.workspace.port.in.WorkspaceContract.SeasonRoundResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.WorkspaceResult;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.RoundTimingStatus;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import com.personal.baton.domain.workspace.Team;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceProjectionReaderTest {

    private static final Instant NOW = Instant.parse("2026-07-31T12:00:00Z");

    @DisplayName("워크스페이스 projection은 한 요청에서 고정한 시각을 모든 시간 계산에 공유한다")
    @Test
    void sharesOneFixedClockAcrossProjectionCalculations() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        Clock clock = mock(Clock.class);
        WorkspaceScope scope = workspaceScope();
        SeasonRound round = SeasonRound.createAutomatic(
                UUID.randomUUID(),
                scope.season().getId(),
                "7월 마지막 모임",
                LocalDate.of(2026, 7, 31),
                NOW
        );
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(repository.findSeasonsByTeamId(scope.team().getId()))
                .thenReturn(List.of(scope.season()));
        when(repository.findSeasonRoundsBySeasonId(scope.season().getId()))
                .thenReturn(List.of(round));
        when(repository.findRoutineExecutionsBySeasonRoundIds(List.of(round.getId())))
                .thenReturn(List.of());

        WorkspaceResult result = reader(repository, clock).read(scope);

        assertThat(result.rounds()).singleElement()
                .extracting(SeasonRoundResult::timingStatus)
                .isEqualTo(RoundTimingStatus.IN_PROGRESS);
        verify(clock, times(1)).instant();
    }

    @DisplayName("역할과 회차가 없으면 workspace projection은 자식 bulk 조회를 생략한다")
    @Test
    void skipsChildBulkQueriesWhenParentsAreEmpty() {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        WorkspaceScope scope = workspaceScope();
        when(repository.findSeasonsByTeamId(scope.team().getId()))
                .thenReturn(List.of(scope.season()));

        WorkspaceResult result = reader(repository, clock).read(scope);

        assertThat(result.rounds()).isEmpty();
        assertThat(result.handoffItems()).isEmpty();
        assertThat(result.resources()).isEmpty();
        assertThat(result.roleHandoffs()).isEmpty();
        verify(repository, never()).findRoutineExecutionsBySeasonRoundIds(anyList());
        verify(repository, never()).findHandoffItemsByRoleIds(anyList());
        verify(repository, never()).findRoleResourcesByRoleIds(anyList());
        verify(repository, never()).findRoleHandoffsByRoleIds(anyList());
    }

    private WorkspaceProjectionReader reader(WorkspaceRepository repository, Clock clock) {
        return new WorkspaceProjectionReader(
                repository,
                clock,
                new ContinuitySignalAnalyzer(),
                new WorkspaceResultMapper(clock)
        );
    }

    private WorkspaceScope workspaceScope() {
        UUID teamId = UUID.randomUUID();
        Team team = Team.create(teamId, "리더 분리 스터디", "0".repeat(64));
        Season season = Season.create(
                UUID.randomUUID(),
                teamId,
                "2026 여름",
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 8, 31),
                "Etc/UTC"
        );
        return new WorkspaceScope(team, season);
    }
}
