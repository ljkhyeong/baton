package com.personal.baton.adapter.in.web.identity;

import java.util.UUID;

public record IdentitySessionResponse(
        boolean authenticated,
        UUID accountId,
        String csrfHeaderName,
        String csrfToken,
        boolean oidcEnabled
) {

    static IdentitySessionResponse anonymous(boolean oidcEnabled) {
        return new IdentitySessionResponse(false, null, null, null, oidcEnabled);
    }

    static IdentitySessionResponse authenticated(
            UUID accountId,
            String csrfHeaderName,
            String csrfToken,
            boolean oidcEnabled
    ) {
        return new IdentitySessionResponse(
                true,
                accountId,
                csrfHeaderName,
                csrfToken,
                oidcEnabled
        );
    }
}
