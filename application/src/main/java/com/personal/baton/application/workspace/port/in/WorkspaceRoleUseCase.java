package com.personal.baton.application.workspace.port.in;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceRoleUseCase {

    RoleResult createRoleAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateRoleCommand command
    );

    @Transactional
    default RoleResult createRole(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleCommand command
    ) {
        return createRoleAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    RoleResult updateRoleAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            WorkspaceAuthorization authorization,
            UpdateRoleCommand command
    );

    @Transactional
    default RoleResult updateRole(
            UUID teamId,
            UUID seasonId,
            UUID roleId,
            String accessKey,
            UpdateRoleCommand command
    ) {
        return updateRoleAuthorized(teamId, seasonId, roleId, legacy(accessKey), command);
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record CreateRoleCommand(
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
    }

    record UpdateRoleCommand(
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
    }

    record RoleResult(
            UUID id,
            String name,
            String purpose,
            UUID currentMemberId,
            UUID nextMemberId,
            LocalDate assignmentStartDate,
            LocalDate assignmentEndDate,
            List<String> responsibilities,
            String risk
    ) {
    }
}
