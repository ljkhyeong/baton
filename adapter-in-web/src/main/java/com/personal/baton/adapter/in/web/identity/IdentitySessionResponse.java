package com.personal.baton.adapter.in.web.identity;

import java.util.UUID;

public record IdentitySessionResponse(
        boolean authenticated,
        UUID accountId,
        String csrfHeaderName,
        String csrfToken
) {

    static IdentitySessionResponse anonymous() {
        return new IdentitySessionResponse(false, null, null, null);
    }

    static IdentitySessionResponse authenticated(
            UUID accountId,
            String csrfHeaderName,
            String csrfToken
    ) {
        return new IdentitySessionResponse(
                true,
                accountId,
                csrfHeaderName,
                csrfToken
        );
    }
}
