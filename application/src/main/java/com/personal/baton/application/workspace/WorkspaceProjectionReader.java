package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceQueryUseCase.TeamResult;
import com.personal.baton.application.workspace.port.in.WorkspaceQueryUseCase.WorkspaceResult;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.Decision;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.Season;
import com.personal.baton.domain.workspace.SeasonRound;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkspaceProjectionReader {

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final ContinuitySignalAnalyzer continuitySignalAnalyzer;
    private final WorkspaceResultMapper resultMapper;

    WorkspaceProjectionReader(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceResultMapper resultMapper
    ) {
        this.repository = repository;
        this.clock = clock;
        this.continuitySignalAnalyzer = new ContinuitySignalAnalyzer();
        this.resultMapper = resultMapper;
    }

    WorkspaceResult read(WorkspaceScope scope) {
        UUID teamId = scope.team().getId();
        UUID seasonId = scope.season().getId();
        List<Member> members = repository.findMembersByTeamId(teamId);
        List<Season> seasons = repository.findSeasonsByTeamId(teamId);
        List<Role> roles = repository.findRolesByTeamIdAndSeasonId(teamId, seasonId);
        List<Routine> routines = repository.findRoutinesBySeasonId(seasonId);
        List<SeasonRound> rounds = repository.findSeasonRoundsBySeasonId(seasonId);
        List<RoutineExecution> executions = rounds.isEmpty()
                ? List.of()
                : repository.findRoutineExecutionsBySeasonRoundIds(
                        rounds.stream().map(SeasonRound::getId).toList()
                );
        List<Decision> decisions = repository.findDecisionsBySeasonId(seasonId);
        List<UUID> roleIds = roles.stream().map(Role::getId).toList();
        List<HandoffItem> handoffItems = roleIds.isEmpty()
                ? List.of()
                : repository.findHandoffItemsByRoleIds(roleIds);
        List<RoleResource> resources = roleIds.isEmpty()
                ? List.of()
                : repository.findRoleResourcesByRoleIds(roleIds);
        List<RoleHandoff> roleHandoffs = roleIds.isEmpty()
                ? List.of()
                : repository.findRoleHandoffsByRoleIds(roleIds);

        Map<UUID, Member> membersById = indexMembers(members);
        Map<UUID, List<RoutineExecution>> executionsByRoundId =
                executionsByRoundId(executions);
        Clock projectionClock = Clock.fixed(clock.instant(), clock.getZone());
        return new WorkspaceResult(
                new TeamResult(scope.team().getId(), scope.team().getName()),
                resultMapper.toSeasonResult(scope.season()),
                seasons.stream().map(resultMapper::toSeasonSummaryResult).toList(),
                members.stream().map(resultMapper::toMemberResult).toList(),
                roles.stream().map(resultMapper::toRoleResult).toList(),
                routines.stream().map(resultMapper::toRoutineResult).toList(),
                rounds.stream()
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
                handoffItems.stream().map(resultMapper::toHandoffItemResult).toList(),
                resources.stream().map(resultMapper::toRoleResourceResult).toList(),
                roleHandoffs.stream().map(resultMapper::toRoleHandoffResult).toList(),
                continuitySignalAnalyzer.analyze(
                        projectionClock,
                        scope.season(),
                        members,
                        roles,
                        routines,
                        rounds,
                        executions,
                        handoffItems,
                        resources,
                        roleHandoffs
                )
        );
    }

    private Map<UUID, Member> indexMembers(List<Member> members) {
        Map<UUID, Member> result = new HashMap<>();
        for (Member member : members) {
            result.put(member.getId(), member);
        }
        return result;
    }

    private Map<UUID, List<RoutineExecution>> executionsByRoundId(
            List<RoutineExecution> executions
    ) {
        Map<UUID, List<RoutineExecution>> result = new HashMap<>();
        for (RoutineExecution execution : executions) {
            result.computeIfAbsent(execution.getSeasonRoundId(), ignored -> new ArrayList<>())
                    .add(execution);
        }
        return result;
    }
}
