package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.CreateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.DecisionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.HandoffItemResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoleResourceResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.UpdateDecisionCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.UpdateHandoffItemCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.UpdateRoleResourceCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceRecordsUseCase;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspaceRecordsService implements WorkspaceRecordsUseCase {

    private final WorkspaceScopeAuthorizer scopeAuthorizer;
    private final WorkspaceDecisionCoordinator decisionCoordinator;
    private final WorkspaceHandoffItemCoordinator handoffItemCoordinator;
    private final WorkspaceRoleResourceCoordinator roleResourceCoordinator;

    public WorkspaceRecordsService(
            WorkspaceScopeAuthorizer scopeAuthorizer,
            WorkspaceDecisionCoordinator decisionCoordinator,
            WorkspaceHandoffItemCoordinator handoffItemCoordinator,
            WorkspaceRoleResourceCoordinator roleResourceCoordinator
    ) {
        this.scopeAuthorizer = scopeAuthorizer;
        this.decisionCoordinator = decisionCoordinator;
        this.handoffItemCoordinator = handoffItemCoordinator;
        this.roleResourceCoordinator = roleResourceCoordinator;
    }

    @Override
    @Transactional
    public DecisionResult createDecision(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateDecisionCommand command
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return decisionCoordinator.create(teamId, seasonId, idempotencyKey, command);
    }

    @Override
    @Transactional
    public DecisionResult updateDecision(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            UpdateDecisionCommand command
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        return decisionCoordinator.update(teamId, seasonId, decisionId, command);
    }

    @Override
    @Transactional
    public DecisionResult updateDecisionArchive(
            UUID teamId,
            UUID seasonId,
            UUID decisionId,
            String accessKey,
            boolean archived
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        return decisionCoordinator.updateArchive(teamId, seasonId, decisionId, archived);
    }

    @Override
    @Transactional
    public HandoffItemResult createHandoffItem(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateHandoffItemCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return handoffItemCoordinator.create(teamId, seasonId, idempotencyKey, command);
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItem(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            UpdateHandoffItemCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return handoffItemCoordinator.update(teamId, seasonId, itemId, command);
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItemCompletion(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean completed
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return handoffItemCoordinator.updateCompletion(teamId, seasonId, itemId, completed);
    }

    @Override
    @Transactional
    public HandoffItemResult updateHandoffItemArchive(
            UUID teamId,
            UUID seasonId,
            UUID itemId,
            String accessKey,
            boolean archived
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return handoffItemCoordinator.updateArchive(teamId, seasonId, itemId, archived);
    }

    @Override
    @Transactional
    public RoleResourceResult createRoleResource(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleResourceCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return roleResourceCoordinator.create(teamId, seasonId, idempotencyKey, command);
    }

    @Override
    @Transactional
    public RoleResourceResult updateRoleResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            UpdateRoleResourceCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleResourceCoordinator.update(teamId, seasonId, resourceId, command);
    }

    @Override
    @Transactional
    public RoleResourceResult updateRoleResourceArchive(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            boolean archived
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleResourceCoordinator.updateArchive(teamId, seasonId, resourceId, archived);
    }
}
