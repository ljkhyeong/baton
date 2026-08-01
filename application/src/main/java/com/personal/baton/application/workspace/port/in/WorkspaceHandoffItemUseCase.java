package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.HandoffCategory;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceHandoffItemUseCase {

    HandoffItemResult createHandoffItemAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateHandoffItemCommand command
    );

    @Transactional
    default HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateHandoffItemCommand command
    ) {
        return createHandoffItemAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    HandoffItemResult updateHandoffItemAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            WorkspaceAuthorization authorization,
            UpdateHandoffItemCommand command
    );

    @Transactional
    default HandoffItemResult updateHandoffItem(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            UpdateHandoffItemCommand command
    ) {
        return updateHandoffItemAuthorized(
                teamId,
                seasonId,
                itemId,
                legacy(accessKey),
                command
        );
    }

    HandoffItemResult updateHandoffItemCompletionAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            WorkspaceAuthorization authorization,
            boolean completed
    );

    @Transactional
    default HandoffItemResult updateHandoffItemCompletion(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean completed
    ) {
        return updateHandoffItemCompletionAuthorized(
                teamId,
                seasonId,
                itemId,
                legacy(accessKey),
                completed
        );
    }

    HandoffItemResult updateHandoffItemArchiveAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            WorkspaceAuthorization authorization,
            boolean archived
    );

    @Transactional
    default HandoffItemResult updateHandoffItemArchive(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean archived
    ) {
        return updateHandoffItemArchiveAuthorized(
                teamId,
                seasonId,
                itemId,
                legacy(accessKey),
                archived
        );
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record CreateHandoffItemCommand(
            UUID roleId,
            String label,
            HandoffCategory category
    ) {
    }

    record UpdateHandoffItemCommand(
            UUID roleId,
            String label,
            HandoffCategory category
    ) {
    }

    record HandoffItemResult(
            UUID id,
            UUID roleId,
            String label,
            HandoffCategory category,
            boolean completed,
            Instant createdAt,
            Instant archivedAt
    ) {
    }
}
