package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.UpdateRoleCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.Role;
import com.personal.baton.domain.workspace.Season;
import java.util.Objects;
import java.util.UUID;

@Component
final class WorkspaceRoleCoordinator {

    private final WorkspaceRepository repository;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceMemberResolver memberResolver;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceRolePolicy rolePolicy;
    private final WorkspaceResultMapper resultMapper;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    WorkspaceRoleCoordinator(
            WorkspaceRepository repository,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceMemberResolver memberResolver,
            WorkspaceRoleResolver roleResolver,
            WorkspaceRolePolicy rolePolicy,
            WorkspaceResultMapper resultMapper,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.repository = repository;
        this.contentIdempotency = contentIdempotency;
        this.memberResolver = memberResolver;
        this.roleResolver = roleResolver;
        this.rolePolicy = rolePolicy;
        this.resultMapper = resultMapper;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
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
        Role saved = repository.saveRole(role);
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return resultMapper.toRoleResult(saved);
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
                command.name(),
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
