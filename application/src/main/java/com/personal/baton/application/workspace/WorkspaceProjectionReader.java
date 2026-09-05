package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.TeamResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.WorkspaceResult;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceSeasonRepository;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
final class WorkspaceProjectionReader {

    private final WorkspaceSeasonRepository seasonRepository;
    private final WorkspaceRecordsRepository recordsRepository;
    private final Clock clock;
    private final WorkspaceContinuitySnapshotReader continuitySnapshotReader;
    private final ContinuitySignalAnalyzer continuitySignalAnalyzer;
    private final WorkspaceResultMapper resultMapper;

    WorkspaceProjectionReader(
            WorkspaceSeasonRepository seasonRepository,
            WorkspaceRecordsRepository recordsRepository,
            Clock clock,
            WorkspaceContinuitySnapshotReader continuitySnapshotReader,
            ContinuitySignalAnalyzer continuitySignalAnalyzer,
            WorkspaceResultMapper resultMapper
    ) {
        this.seasonRepository = seasonRepository;
        this.recordsRepository = recordsRepository;
        this.clock = clock;
        this.continuitySnapshotReader = continuitySnapshotReader;
        this.continuitySignalAnalyzer = continuitySignalAnalyzer;
        this.resultMapper = resultMapper;
    }

    WorkspaceResult read(WorkspaceScope scope) {
        UUID teamId = scope.team().getId();
        UUID seasonId = scope.season().getId();
        List<Season> seasons = seasonRepository.findSeasonsByTeamId(teamId);
        List<Decision> decisions = recordsRepository.findDecisionsBySeasonId(seasonId);
        WorkspaceContinuitySnapshot snapshot = continuitySnapshotReader.read(teamId, seasonId);

        Map<UUID, Member> membersById = snapshot.members().stream()
                .collect(Collectors.toMap(Member::getId, Function.identity()));
        Map<UUID, List<RoutineExecution>> executionsByRoundId = snapshot.executions().stream()
                .collect(Collectors.groupingBy(RoutineExecution::getSeasonRoundId));
        Clock projectionClock = Clock.fixed(clock.instant(), clock.getZone());
        return new WorkspaceResult(
                new TeamResult(scope.team().getId(), scope.team().getName(), scope.team().isAccountAccessEnabled(), scope.permission()),
                resultMapper.toSeasonResult(scope.season()),
                seasons.stream().map(resultMapper::toSeasonSummaryResult).toList(),
                snapshot.members().stream().map(resultMapper::toMemberResult).toList(),
                snapshot.roles().stream().map(resultMapper::toRoleResult).toList(),
                snapshot.routines().stream().map(resultMapper::toRoutineResult).toList(),
                snapshot.rounds().stream()
                        .map(round -> resultMapper.toSeasonRoundResult(
                                round,
                                executionsByRoundId.getOrDefault(round.getId(), List.of()),
                                scope.season(),
                                projectionClock
                        ))
                        .toList(),
                decisions.stream()
                        .map(decision -> resultMapper.toDecisionResult(decision, membersById))
                        .toList(),
                snapshot.handoffItems().stream().map(resultMapper::toHandoffItemResult).toList(),
                snapshot.resources().stream().map(resultMapper::toRoleResourceResult).toList(),
                snapshot.roleHandoffs().stream().map(resultMapper::toRoleHandoffResult).toList(),
                continuitySignalAnalyzer.analyze(
                        projectionClock,
                        scope.season(),
                        snapshot.members(),
                        snapshot.roles(),
                        snapshot.routines(),
                        snapshot.rounds(),
                        snapshot.executions(),
                        snapshot.handoffItems(),
                        snapshot.resources(),
                        snapshot.roleHandoffs()
                )
        );
    }

}
