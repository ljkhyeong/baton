package com.personal.baton.adapter.in.web.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.AuthenticatedPrincipal;

public final class BatonAccountPrincipal implements AuthenticatedPrincipal, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID accountId;

    public BatonAccountPrincipal(UUID accountId) {
        this.accountId = Objects.requireNonNull(accountId);
    }

    public UUID accountId() {
        return accountId;
    }

    @Override
    public String getName() {
        return accountId.toString();
    }

    @Override
    public boolean equals(Object candidate) {
        return candidate instanceof BatonAccountPrincipal other
                && accountId.equals(other.accountId);
    }

    @Override
    public int hashCode() {
        return accountId.hashCode();
    }

    @Override
    public String toString() {
        return "BatonAccountPrincipal[accountId=" + accountId + "]";
    }
}
