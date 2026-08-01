package com.personal.baton.application.workspace.port.in;

import java.time.Instant;
import java.util.UUID;

public interface WorkspaceRoleResourceQueryUseCase {

    RoleResourceResult getRoleResourceForGrantAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            WorkspaceAuthorization authorization
    );

    record RoleResourceResult(
            UUID id,
            UUID roleId,
            String title,
            String url,
            String description,
            Instant createdAt
    ) {
    }
}
