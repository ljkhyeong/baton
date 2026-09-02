package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface WorkspacePeopleUseCase {

    WorkspaceContract.MemberResult createMember(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspacePeopleCommands.CreateMemberCommand command
    );

    WorkspaceContract.MemberResult updateMember(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            WorkspacePeopleCommands.UpdateMemberCommand command
    );

    WorkspaceContract.MemberResult updateMemberDeactivation(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            boolean deactivated
    );

    WorkspaceContract.RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspacePeopleCommands.CreateRoleCommand command
    );

    WorkspaceContract.RoleResult updateRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String accessKey,
            WorkspacePeopleCommands.UpdateRoleCommand command
    );

    WorkspaceContract.RoleHandoffTransitionResult prepareRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            String accessKey,
            WorkspacePeopleCommands.PrepareRoleHandoffCommand command
    );

    WorkspaceContract.RoleHandoffTransitionResult transferRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            WorkspacePeopleCommands.TransferRoleHandoffCommand command
    );

    WorkspaceContract.RoleHandoffTransitionResult acceptRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            WorkspacePeopleCommands.ConfirmRoleHandoffCommand command
    );

    WorkspaceContract.RoleHandoffTransitionResult cancelRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            WorkspacePeopleCommands.ConfirmRoleHandoffCommand command
    );
}
