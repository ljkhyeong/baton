package com.personal.baton.application.link.port.in;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public interface RoleResourceLinkUseCase {

    OpenRoleResourceLinkResult openRoleResourceLink(
            UUID teamId,
            UUID seasonId,
            UUID resourceId,
            String accessKey,
            String idempotencyKey,
            Instant expiresAt
    );

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
