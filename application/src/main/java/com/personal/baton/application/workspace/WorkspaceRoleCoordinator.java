package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoleCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Season;
import java.util.Objects;
import java.util.UUID;

final class WorkspaceRoleCoordinator {

    private final WorkspaceRepository repository;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceMemberResolver memberResolver;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceRolePolicy rolePolicy;
    private final WorkspaceResultMapper resultMapper;

    WorkspaceRoleCoordinator(
            WorkspaceRepository repository,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceMemberResolver memberResolver,
            WorkspaceRoleResolver roleResolver,
            WorkspaceRolePolicy rolePolicy,
            WorkspaceResultMapper resultMapper
    ) {
        this.repository = repository;
        this.contentIdempotency = contentIdempotency;
        this.memberResolver = memberResolver;
        this.roleResolver = roleResolver;
        this.rolePolicy = rolePolicy;
        this.resultMapper = resultMapper;
    }

    RoleResult create(
            UUID teamId,
            Season season,
            String idempotencyKey,
            CreateRoleCommand command
    ) {
        UUID seasonId = season.getId();
        Role role = Role.create(
                UUID.randomUUID(),
                teamId,
                seasonId,
                command.name(),
                command.purpose(),
                command.currentMemberId(),
                command.nextMemberId(),
                command.assignmentStartDate(),
                command.assignmentEndDate(),
                command.responsibilities(),
                command.risk()
        );
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE,
                idempotencyKey,
                contentIdempotency.fingerprintRoleRequest(teamId, seasonId, role),
                role.getId()
        );
        if (attempt.replayResourceId() != null) {
            Role existing = repository.findRoleById(attempt.replayResourceId())
                    .filter(found -> found.getTeamId().equals(teamId))
                    .filter(found -> found.getSeasonId().equals(seasonId))
                    .orElseThrow(() ->
                            contentIdempotency.missingResource(ContentCreationOperation.ROLE));
            return resultMapper.toRoleResult(existing);
        }
        memberResolver.requireActiveMembersForNewReferences(
                teamId,
                role.getCurrentMemberId(),
                role.getNextMemberId()
        );
        rolePolicy.validateAssignmentDates(
                season,
                role.getAssignmentStartDate(),
                role.getAssignmentEndDate()
        );
        contentIdempotency.reserve(attempt);
        return resultMapper.toRoleResult(repository.saveRole(role));
    }

    RoleResult update(
            UUID teamId,
            Season season,
            UUID roleId,
            UpdateRoleCommand command
    ) {
        UUID seasonId = season.getId();
        Role role = roleResolver.requireRoleForUpdate(teamId, seasonId, roleId);
        rolePolicy.requireUpdateAllowed(
                role,
                command.currentMemberId(),
                command.nextMemberId(),
                command.assignmentStartDate(),
                command.assignmentEndDate()
        );
        String normalizedName = Role.normalizeName(command.name());
        memberResolver.requireActiveMembersForNewReferences(
                teamId,
                Objects.equals(role.getCurrentMemberId(), command.currentMemberId())
                        ? null
                        : command.currentMemberId(),
                Objects.equals(role.getNextMemberId(), command.nextMemberId())
                        ? null
                        : command.nextMemberId()
        );
        rolePolicy.validateAssignmentDates(
                season,
                command.assignmentStartDate(),
                command.assignmentEndDate()
        );
        role.update(
                normalizedName,
                command.purpose(),
                command.currentMemberId(),
                command.nextMemberId(),
                command.assignmentStartDate(),
                command.assignmentEndDate(),
                command.responsibilities(),
                command.risk()
        );
        return resultMapper.toRoleResult(repository.saveRole(role));
    }
}
