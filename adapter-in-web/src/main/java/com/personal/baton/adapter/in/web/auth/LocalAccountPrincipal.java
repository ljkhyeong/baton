package com.personal.baton.adapter.in.web.auth;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class LocalAccountPrincipal implements
        AuthenticatedAccountPrincipal,
        UserDetails,
        CredentialsContainer {

    private static final List<GrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT"));

    private final UUID accountId;
    private final String email;
    private String passwordHash;

    LocalAccountPrincipal(
            UUID accountId,
            String email,
            String passwordHash
    ) {
        this.accountId = accountId;
        this.email = email;
        this.passwordHash = passwordHash;
    }

    @Override
    public UUID accountId() {
        return accountId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AUTHORITIES;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
