package com.personal.baton.application.link.port.out;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public interface RoleResourceLinkPort {

    LinkNavigation createNavigation(
            URI resourceUrl,
            UUID idempotencyKey,
            Instant expiresAt
    );

    record LinkNavigation(
            URI navigationUrl,
            boolean managedByBatonGo,
            Instant expiresAt
    ) {
    }
}
