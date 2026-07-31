package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResourceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoleResourceCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.RoleResource;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

final class WorkspaceRoleResourceCoordinator {

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceRolePolicy rolePolicy;
    private final WorkspaceResultMapper resultMapper;

    WorkspaceRoleResourceCoordinator(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceRoleResolver roleResolver,
            WorkspaceRolePolicy rolePolicy,
            WorkspaceResultMapper resultMapper
    ) {
        this.repository = repository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.roleResolver = roleResolver;
        this.rolePolicy = rolePolicy;
        this.resultMapper = resultMapper;
    }

    RoleResourceResult create(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            CreateRoleResourceCommand command
    ) {
        RoleResource resource = RoleResource.create(
                UUID.randomUUID(),
                command.roleId(),
                command.title(),
                command.url(),
                command.description(),
                Instant.now(clock)
        );
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.ROLE_RESOURCE,
                idempotencyKey,
                contentIdempotency.fingerprintRoleResourceRequest(teamId, seasonId, resource),
                resource.getId()
        );
        if (attempt.replayResourceId() != null) {
            RoleResource existing = repository.findRoleResourceById(attempt.replayResourceId())
                    .orElseThrow(() -> contentIdempotency.missingResource(
                            ContentCreationOperation.ROLE_RESOURCE
                    ));
            roleResolver.requireRole(teamId, seasonId, existing.getRoleId());
            return resultMapper.toRoleResourceResult(existing);
        }
        rolePolicy.requireEditableHandoffRoles(teamId, seasonId, resource.getRoleId());
        contentIdempotency.reserve(attempt);
        return resultMapper.toRoleResourceResult(repository.saveRoleResource(resource));
    }

    RoleResourceResult update(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            UpdateRoleResourceCommand command
    ) {
        RoleResource resource = requireRoleResource(teamId, seasonId, resourceId);
        rolePolicy.requireEditableHandoffRoles(
                teamId,
                seasonId,
                resource.getRoleId(),
                command.roleId()
        );
        resource.update(command.roleId(), command.title(), command.url(), command.description());
        return resultMapper.toRoleResourceResult(repository.saveRoleResource(resource));
    }

    private RoleResource requireRoleResource(UUID teamId, UUID seasonId, UUID resourceId) {
        RoleResource resource = repository.findRoleResourceById(resourceId)
                .orElseThrow(this::roleResourceNotFound);
        repository.findRoleById(resource.getRoleId())
                .filter(role -> role.getTeamId().equals(teamId))
                .filter(role -> role.getSeasonId().equals(seasonId))
                .orElseThrow(this::roleResourceNotFound);
        return resource;
    }

    private WorkspaceNotFoundException roleResourceNotFound() {
        return new WorkspaceNotFoundException(
                "ROLE_RESOURCE_NOT_FOUND",
                "자료를 찾을 수 없습니다"
        );
    }
}
