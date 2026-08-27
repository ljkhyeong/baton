package com.personal.baton.adapter.in.web.auth;

import java.io.Serial;
import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

public record AccountSessionPrincipal(UUID accountId) implements
        AuthenticatedAccountPrincipal,
        Principal,
        Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public AccountSessionPrincipal {
        Objects.requireNonNull(accountId);
    }

    @Override
    public String getName() {
        return accountId.toString();
    }
}
