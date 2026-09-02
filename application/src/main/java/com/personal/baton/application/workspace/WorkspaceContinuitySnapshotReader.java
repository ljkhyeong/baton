package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.out.WorkspaceOperationsRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.workspace.HandoffItem;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.RoleHandoff;
import com.personal.baton.domain.workspace.RoleResource;
import com.personal.baton.domain.workspace.Routine;
import com.personal.baton.domain.workspace.RoutineExecution;
import com.personal.baton.domain.workspace.SeasonRound;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class WorkspaceContinuitySnapshotReader {

    private final WorkspacePeopleRepository peopleRepository;
    private final WorkspaceOperationsRepository operationsRepository;
    private final WorkspaceRecordsRepository recordsRepository;

    WorkspaceContinuitySnapshotReader(
            WorkspacePeopleRepository peopleRepository,
            WorkspaceOperationsRepository operationsRepository,
            WorkspaceRecordsRepository recordsRepository
    ) {
        this.peopleRepository = peopleRepository;
        this.operationsRepository = operationsRepository;
        this.recordsRepository = recordsRepository;
    }

    WorkspaceContinuitySnapshot read(UUID teamId, UUID seasonId) {
        List<Member> members = peopleRepository.findMembersByTeamId(teamId);
        List<Role> roles = peopleRepository.findRolesByTeamIdAndSeasonId(teamId, seasonId);
        List<Routine> routines = operationsRepository.findRoutinesBySeasonId(seasonId);
        List<SeasonRound> rounds = operationsRepository.findSeasonRoundsBySeasonId(seasonId);
        List<RoutineExecution> executions = rounds.isEmpty()
                ? List.of()
                : operationsRepository.findRoutineExecutionsBySeasonRoundIds(
                        rounds.stream().map(SeasonRound::getId).toList()
                );
        List<UUID> roleIds = roles.stream().map(Role::getId).toList();
        List<HandoffItem> handoffItems = roleIds.isEmpty()
                ? List.of()
                : recordsRepository.findHandoffItemsByRoleIds(roleIds);
        List<RoleResource> resources = roleIds.isEmpty()
                ? List.of()
                : recordsRepository.findRoleResourcesByRoleIds(roleIds);
        List<RoleHandoff> roleHandoffs = roleIds.isEmpty()
                ? List.of()
                : peopleRepository.findRoleHandoffsByRoleIds(roleIds);
        return new WorkspaceContinuitySnapshot(
                members,
                roles,
                routines,
                rounds,
                executions,
                handoffItems,
                resources,
                roleHandoffs
        );
    }
}
