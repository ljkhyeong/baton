package com.personal.baton.application.workspace;

import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.ConfirmRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.CreateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.MemberResult;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.PrepareRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoleHandoffTransitionResult;
import com.personal.baton.application.workspace.port.in.WorkspaceContract.RoleResult;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.TransferRoleHandoffCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.UpdateMemberCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleCommands.UpdateRoleCommand;
import com.personal.baton.application.workspace.port.in.WorkspacePeopleUseCase;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkspacePeopleService implements WorkspacePeopleUseCase {

    private final WorkspaceScopeAuthorizer scopeAuthorizer;
    private final WorkspaceMemberCoordinator memberCoordinator;
    private final WorkspaceRoleCoordinator roleCoordinator;
    private final WorkspaceRoleHandoffCoordinator roleHandoffCoordinator;
    private final BriefContinuitySignalRecorder briefContinuitySignalRecorder;

    public WorkspacePeopleService(
            WorkspaceScopeAuthorizer scopeAuthorizer,
            WorkspaceMemberCoordinator memberCoordinator,
            WorkspaceRoleCoordinator roleCoordinator,
            WorkspaceRoleHandoffCoordinator roleHandoffCoordinator,
            BriefContinuitySignalRecorder briefContinuitySignalRecorder
    ) {
        this.scopeAuthorizer = scopeAuthorizer;
        this.memberCoordinator = memberCoordinator;
        this.roleCoordinator = roleCoordinator;
        this.roleHandoffCoordinator = roleHandoffCoordinator;
        this.briefContinuitySignalRecorder = briefContinuitySignalRecorder;
    }

    @Override
    @Transactional
    public MemberResult createMember(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateMemberCommand command
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return memberCoordinator.create(teamId, seasonId, idempotencyKey, command);
    }

    @Override
    @Transactional
    public MemberResult updateMember(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            UpdateMemberCommand command
    ) {
        scopeAuthorizer.authorizeMutation(teamId, seasonId, accessKey);
        return memberCoordinator.update(teamId, memberId, command);
    }

    @Override
    @Transactional
    public MemberResult updateMemberDeactivation(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            boolean deactivated
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return memberCoordinator.updateDeactivation(teamId, seasonId, memberId, deactivated);
    }

    @Override
    @Transactional
    public RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return roleCoordinator.create(teamId, scope.season(), idempotencyKey, command);
    }

    @Override
    @Transactional
    public RoleResult updateRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String accessKey,
            UpdateRoleCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        RoleResult result = roleCoordinator.update(teamId, scope.season(), roleId, command);
        briefContinuitySignalRecorder.reconcileSeason(teamId, seasonId);
        return result;
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult prepareRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            String accessKey,
            PrepareRoleHandoffCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        WorkspaceIdempotencyKeyPolicy.requireValid(idempotencyKey);
        return roleHandoffCoordinator.prepare(
                teamId,
                scope.season(),
                roleId,
                idempotencyKey,
                command
        );
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult transferRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            TransferRoleHandoffCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleHandoffCoordinator.transfer(teamId, seasonId, roleId, handoffId, command);
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult acceptRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        WorkspaceScope scope = scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleHandoffCoordinator.accept(teamId, scope.season(), roleId, handoffId, command);
    }

    @Override
    @Transactional
    public RoleHandoffTransitionResult cancelRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            ConfirmRoleHandoffCommand command
    ) {
        scopeAuthorizer.authorizeSeasonForUpdate(teamId, seasonId, accessKey);
        return roleHandoffCoordinator.cancel(teamId, seasonId, roleId, handoffId, command);
    }
}
