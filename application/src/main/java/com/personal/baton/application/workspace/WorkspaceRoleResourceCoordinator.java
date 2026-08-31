package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.RoleResourceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateRoleResourceCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.application.watch.WatchMonitorChangeRecorder;
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
    private final WatchMonitorChangeRecorder watchMonitorChangeRecorder;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    WorkspaceRoleResourceCoordinator(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceRoleResolver roleResolver,
            WorkspaceRolePolicy rolePolicy,
            WorkspaceResultMapper resultMapper,
            WatchMonitorChangeRecorder watchMonitorChangeRecorder,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.repository = repository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.roleResolver = roleResolver;
        this.rolePolicy = rolePolicy;
        this.resultMapper = resultMapper;
        this.watchMonitorChangeRecorder = watchMonitorChangeRecorder;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
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
        RoleResource savedResource = repository.saveRoleResource(resource);
        watchMonitorChangeRecorder.recordCreated(savedResource);
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return resultMapper.toRoleResourceResult(savedResource);
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
        String previousUrl = resource.getUrl();
        UUID previousRoleId = resource.getRoleId();
        resource.update(command.roleId(), command.title(), command.url(), command.description());
        RoleResource savedResource = repository.saveRoleResource(resource);
        watchMonitorChangeRecorder.recordUpdated(previousUrl, savedResource);
        if (!previousRoleId.equals(savedResource.getRoleId())) {
            briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        }
        return resultMapper.toRoleResourceResult(savedResource);
    }

    RoleResourceResult updateArchive(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            boolean archived
    ) {
        RoleResource resource = requireRoleResource(teamId, seasonId, resourceId);
        rolePolicy.requireEditableHandoffRoles(teamId, seasonId, resource.getRoleId());
        String previousUrl = resource.getUrl();
        boolean changed = (resource.getArchivedAt() != null) != archived;
        resource.updateArchive(archived, Instant.now(clock));
        RoleResource savedResource = repository.saveRoleResource(resource);
        watchMonitorChangeRecorder.recordUpdated(previousUrl, savedResource);
        if (changed) {
            briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        }
        return resultMapper.toRoleResourceResult(savedResource);
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
