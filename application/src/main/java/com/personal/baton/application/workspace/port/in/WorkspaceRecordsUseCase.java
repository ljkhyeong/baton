package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface WorkspaceRecordsUseCase {

    WorkspaceUseCase.DecisionResult createDecision(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceUseCase.CreateDecisionCommand command
    );

    WorkspaceUseCase.DecisionResult updateDecision(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            WorkspaceUseCase.UpdateDecisionCommand command
    );

    WorkspaceUseCase.DecisionResult updateDecisionArchive(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            boolean archived
    );

    WorkspaceUseCase.HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceUseCase.CreateHandoffItemCommand command
    );

    WorkspaceUseCase.HandoffItemResult updateHandoffItem(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            WorkspaceUseCase.UpdateHandoffItemCommand command
    );

    WorkspaceUseCase.HandoffItemResult updateHandoffItemCompletion(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean completed
    );

    WorkspaceUseCase.HandoffItemResult updateHandoffItemArchive(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean archived
    );

    WorkspaceUseCase.RoleResourceResult createRoleResource(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceUseCase.CreateRoleResourceCommand command
    );

    WorkspaceUseCase.RoleResourceResult updateRoleResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            WorkspaceUseCase.UpdateRoleResourceCommand command
    );

    WorkspaceUseCase.RoleResourceResult updateRoleResourceArchive(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            boolean archived
    );
}
