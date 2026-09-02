package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface WorkspaceRecordsUseCase {

    WorkspaceContract.DecisionResult createDecision(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceContract.CreateDecisionCommand command
    );

    WorkspaceContract.DecisionResult updateDecision(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            WorkspaceContract.UpdateDecisionCommand command
    );

    WorkspaceContract.DecisionResult updateDecisionArchive(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            boolean archived
    );

    WorkspaceContract.HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceContract.CreateHandoffItemCommand command
    );

    WorkspaceContract.HandoffItemResult updateHandoffItem(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            WorkspaceContract.UpdateHandoffItemCommand command
    );

    WorkspaceContract.HandoffItemResult updateHandoffItemCompletion(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean completed
    );

    WorkspaceContract.HandoffItemResult updateHandoffItemArchive(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean archived
    );

    WorkspaceContract.RoleResourceResult createRoleResource(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceContract.CreateRoleResourceCommand command
    );

    WorkspaceContract.RoleResourceResult updateRoleResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            WorkspaceContract.UpdateRoleResourceCommand command
    );

    WorkspaceContract.RoleResourceResult updateRoleResourceArchive(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            boolean archived
    );
}
