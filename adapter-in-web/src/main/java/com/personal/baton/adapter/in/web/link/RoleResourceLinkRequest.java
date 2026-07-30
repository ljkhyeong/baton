package com.personal.baton.adapter.in.web.link;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record RoleResourceLinkRequest(
        @NotNull Instant expiresAt
) {
}
