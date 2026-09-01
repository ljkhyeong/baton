package com.personal.baton.application.workspace.port.in;

import java.util.UUID;

public interface WorkspacePeopleUseCase {

    WorkspaceUseCase.MemberResult createMember(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceUseCase.CreateMemberCommand command
    );

    WorkspaceUseCase.MemberResult updateMember(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            WorkspaceUseCase.UpdateMemberCommand command
    );

    WorkspaceUseCase.MemberResult updateMemberDeactivation(
            UUID teamId,
            UUID seasonId,
            UUID memberId,
            String accessKey,
            boolean deactivated
    );

    WorkspaceUseCase.RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            WorkspaceUseCase.CreateRoleCommand command
    );

    WorkspaceUseCase.RoleResult updateRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String accessKey,
            WorkspaceUseCase.UpdateRoleCommand command
    );

    WorkspaceUseCase.RoleHandoffTransitionResult prepareRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String idempotencyKey,
            String accessKey,
            WorkspaceUseCase.PrepareRoleHandoffCommand command
    );

    WorkspaceUseCase.RoleHandoffTransitionResult transferRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            WorkspaceUseCase.TransferRoleHandoffCommand command
    );

    WorkspaceUseCase.RoleHandoffTransitionResult acceptRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            WorkspaceUseCase.ConfirmRoleHandoffCommand command
    );

    WorkspaceUseCase.RoleHandoffTransitionResult cancelRoleHandoff(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            UUID handoffId,
            String accessKey,
            WorkspaceUseCase.ConfirmRoleHandoffCommand command
    );
}
