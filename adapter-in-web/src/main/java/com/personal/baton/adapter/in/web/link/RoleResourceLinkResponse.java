package com.personal.baton.adapter.in.web.link;

import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase.OpenRoleResourceLinkResult;
import com.personal.baton.application.link.port.in.RoleResourceLinkUseCase.RoutingMode;
import java.net.URI;
import java.time.Instant;

public record RoleResourceLinkResponse(
        URI navigationUrl,
        RoutingMode routingMode,
        Instant expiresAt
) {

    public static RoleResourceLinkResponse from(OpenRoleResourceLinkResult result) {
        return new RoleResourceLinkResponse(
                result.navigationUrl(),
                result.routingMode(),
                result.expiresAt()
        );
    }
}
