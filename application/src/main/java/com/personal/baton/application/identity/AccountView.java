package com.personal.baton.application.identity;

import com.personal.baton.domain.identity.IdentityProvider;
import java.util.List;
import java.util.UUID;

public record AccountView(
        UUID accountId,
        String displayName,
        List<LinkedIdentityView> identities
) {

    public AccountView {
        identities = List.copyOf(identities);
    }

    public record LinkedIdentityView(
            IdentityProvider provider,
            String email,
            boolean emailVerified
    ) {
    }
}
