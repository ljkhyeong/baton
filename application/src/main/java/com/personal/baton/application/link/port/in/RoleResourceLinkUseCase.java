package com.personal.baton.application.link.port.in;

import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public interface RoleResourceLinkUseCase {

    OpenRoleResourceLinkResult openRoleResourceLinkAuthorized(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            WorkspaceAuthorization authorization,
            String idempotencyKey,
            Instant expiresAt
    );

    default OpenRoleResourceLinkResult openRoleResourceLink(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            String idempotencyKey,
            Instant expiresAt
    ) {
        return openRoleResourceLinkAuthorized(
                teamId,
                seasonId,
                resourceId,
                new WorkspaceAuthorization.LegacyAccessKey(accessKey),
                idempotencyKey,
                expiresAt
        );
    }

    record OpenRoleResourceLinkResult(
            URI navigationUrl,
            RoutingMode routingMode,
            Instant expiresAt
    ) {
    }

    enum RoutingMode {
        DIRECT,
        BATON_GO
    }
}
