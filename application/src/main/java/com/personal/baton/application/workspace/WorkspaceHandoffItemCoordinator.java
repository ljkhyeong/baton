package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.CreateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.HandoffItemResult;
import com.personal.baton.application.workspace.port.in.WorkspaceUseCase.UpdateHandoffItemCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.HandoffItem;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

final class WorkspaceHandoffItemCoordinator {

    private final WorkspaceRepository repository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceRolePolicy rolePolicy;
    private final WorkspaceResultMapper resultMapper;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    WorkspaceHandoffItemCoordinator(
            WorkspaceRepository repository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceRoleResolver roleResolver,
            WorkspaceRolePolicy rolePolicy,
            WorkspaceResultMapper resultMapper,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.repository = repository;
        this.clock = clock;
        this.contentIdempotency = contentIdempotency;
        this.roleResolver = roleResolver;
        this.rolePolicy = rolePolicy;
        this.resultMapper = resultMapper;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
    }

    HandoffItemResult create(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            CreateHandoffItemCommand command
    ) {
        HandoffItem item = HandoffItem.create(
                UUID.randomUUID(),
                command.roleId(),
                command.label(),
                command.category(),
                false,
                Instant.now(clock)
        );
        ContentCreationAttempt attempt = contentIdempotency.prepare(
                teamId,
                seasonId,
                ContentCreationOperation.HANDOFF_ITEM,
                idempotencyKey,
                contentIdempotency.fingerprintHandoffItemRequest(teamId, seasonId, item),
                item.getId()
        );
        if (attempt.replayResourceId() != null) {
            HandoffItem existing = repository.findHandoffItemById(attempt.replayResourceId())
                    .orElseThrow(() -> contentIdempotency.missingResource(
                            ContentCreationOperation.HANDOFF_ITEM
                    ));
            roleResolver.requireRole(teamId, seasonId, existing.getRoleId());
            return resultMapper.toHandoffItemResult(existing);
        }
        rolePolicy.requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
        contentIdempotency.reserve(attempt);
        HandoffItem saved = repository.saveHandoffItem(item);
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return resultMapper.toHandoffItemResult(saved);
    }

    HandoffItemResult update(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            UpdateHandoffItemCommand command
    ) {
        HandoffItem item = requireActiveHandoffItem(teamId, seasonId, itemId);
        rolePolicy.requireEditableHandoffRoles(
                teamId,
                seasonId,
                item.getRoleId(),
                command.roleId()
        );
        UUID previousRoleId = item.getRoleId();
        item.update(command.roleId(), command.label(), command.category());
        HandoffItem saved = repository.saveHandoffItem(item);
        if (!previousRoleId.equals(saved.getRoleId())) {
            briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        }
        return resultMapper.toHandoffItemResult(saved);
    }

    HandoffItemResult updateCompletion(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            boolean completed
    ) {
        HandoffItem item = requireActiveHandoffItem(teamId, seasonId, itemId);
        rolePolicy.requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
        boolean changed = item.isCompleted() != completed;
        item.updateCompletion(completed);
        HandoffItem saved = repository.saveHandoffItem(item);
        if (changed) {
            briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        }
        return resultMapper.toHandoffItemResult(saved);
    }

    HandoffItemResult updateArchive(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            boolean archived
    ) {
        HandoffItem item = requireHandoffItem(teamId, seasonId, itemId);
        rolePolicy.requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
        boolean changed = (item.getArchivedAt() != null) != archived;
        item.updateArchive(archived, Instant.now(clock));
        HandoffItem saved = repository.saveHandoffItem(item);
        if (changed) {
            briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        }
        return resultMapper.toHandoffItemResult(saved);
    }

    private HandoffItem requireHandoffItem(UUID teamId, UUID seasonId, UUID itemId) {
        HandoffItem item = repository.findHandoffItemById(itemId)
                .orElseThrow(this::handoffItemNotFound);
        repository.findRoleById(item.getRoleId())
                .filter(role -> role.getTeamId().equals(teamId))
                .filter(role -> role.getSeasonId().equals(seasonId))
                .orElseThrow(this::handoffItemNotFound);
        return item;
    }

    private HandoffItem requireActiveHandoffItem(UUID teamId, UUID seasonId, UUID itemId) {
        HandoffItem item = requireHandoffItem(teamId, seasonId, itemId);
        if (item.getArchivedAt() != null) {
            throw handoffItemNotFound();
        }
        return item;
    }

    private WorkspaceNotFoundException handoffItemNotFound() {
        return new WorkspaceNotFoundException(
                "HANDOFF_ITEM_NOT_FOUND",
                "인수인계 항목을 찾을 수 없습니다"
        );
    }
}
