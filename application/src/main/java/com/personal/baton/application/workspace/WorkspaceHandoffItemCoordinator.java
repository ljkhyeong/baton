package com.personal.baton.application.workspace;

import org.springframework.stereotype.Component;
import com.personal.baton.application.workspace.WorkspaceContentIdempotency.ContentCreationAttempt;
import com.personal.baton.application.workspace.error.WorkspaceNotFoundException;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.CreateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.HandoffItemResult;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordCommands.UpdateHandoffItemCommand;
import com.personal.baton.application.workspace.port.out.WorkspaceRecordsRepository;
import com.personal.baton.domain.workspace.ContentCreationOperation;
import com.personal.baton.domain.workspace.HandoffItem;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
final class WorkspaceHandoffItemCoordinator {

    private final WorkspaceRecordsRepository recordsRepository;
    private final Clock clock;
    private final WorkspaceContentIdempotency contentIdempotency;
    private final WorkspaceRoleResolver roleResolver;
    private final WorkspaceRolePolicy rolePolicy;
    private final WorkspaceResultMapper resultMapper;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    WorkspaceHandoffItemCoordinator(
            WorkspaceRecordsRepository recordsRepository,
            Clock clock,
            WorkspaceContentIdempotency contentIdempotency,
            WorkspaceRoleResolver roleResolver,
            WorkspaceRolePolicy rolePolicy,
            WorkspaceResultMapper resultMapper,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.recordsRepository = recordsRepository;
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
            HandoffItem existing = recordsRepository.findHandoffItemById(attempt.replayResourceId())
                    .orElseThrow(() -> contentIdempotency.missingResource(
                            ContentCreationOperation.HANDOFF_ITEM
                    ));
            roleResolver.requireRole(teamId, seasonId, existing.getRoleId());
            return resultMapper.toHandoffItemResult(existing);
        }
        rolePolicy.requireEditableHandoffRoles(teamId, seasonId, item.getRoleId());
        contentIdempotency.reserve(attempt);
        HandoffItem saved = recordsRepository.saveHandoffItem(item);
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
        HandoffItem saved = recordsRepository.saveHandoffItem(item);
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
        HandoffItem saved = recordsRepository.saveHandoffItem(item);
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
        HandoffItem saved = recordsRepository.saveHandoffItem(item);
        if (changed) {
            briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        }
        return resultMapper.toHandoffItemResult(saved);
    }

    private HandoffItem requireHandoffItem(UUID teamId, UUID seasonId, UUID itemId) {
        HandoffItem item = recordsRepository.findHandoffItemById(itemId)
                .orElseThrow(this::handoffItemNotFound);
        roleResolver.requireRole(
                teamId,
                seasonId,
                item.getRoleId(),
                this::handoffItemNotFound
        );
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
