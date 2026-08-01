package com.personal.baton.application.workspace.port.in;

import com.personal.baton.application.workspace.port.in.WorkspaceRoleResourceQueryUseCase.RoleResourceResult;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public interface WorkspaceRoleResourceUseCase {

    RoleResourceResult createRoleResourceAuthorized(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            WorkspaceAuthorization authorization,
            CreateRoleResourceCommand command
    );

    @Transactional
    default RoleResourceResult createRoleResource(
            UUID teamId,
            UUID seasonId,
            String idempotencyKey,
            String accessKey,
            CreateRoleResourceCommand command
    ) {
        return createRoleResourceAuthorized(
                teamId,
                seasonId,
                idempotencyKey,
                legacy(accessKey),
                command
        );
    }

    RoleResourceResult updateRoleResourceAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            WorkspaceAuthorization authorization,
            UpdateRoleResourceCommand command
    );

    @Transactional
    default RoleResourceResult updateRoleResource(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            UpdateRoleResourceCommand command
    ) {
        return updateRoleResourceAuthorized(
                teamId,
                seasonId,
                resourceId,
                legacy(accessKey),
                command
        );
    }

    private static WorkspaceAuthorization legacy(String accessKey) {
        return new WorkspaceAuthorization.LegacyAccessKey(accessKey);
    }

    record CreateRoleResourceCommand(
            UUID roleId,
            String title,
            String url,
            String description
    ) {
    }

    record UpdateRoleResourceCommand(
            UUID roleId,
            String title,
            String url,
            String description
    ) {
    }
}
