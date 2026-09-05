package com.personal.baton.adapter.in.web.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentAuthenticatedAccount {
    private CurrentAuthenticatedAccount() {}
    public static Optional<UUID> accountId() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedAccountPrincipal principal) {
            return Optional.of(principal.accountId());
        }
        return Optional.empty();
    }
}
