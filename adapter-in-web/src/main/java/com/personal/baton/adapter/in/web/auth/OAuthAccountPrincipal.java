package com.personal.baton.adapter.in.web.auth;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

public final class OAuthAccountPrincipal implements
        AuthenticatedAccountPrincipal,
        OAuth2User {

    private static final List<GrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT"));

    private final UUID accountId;
    private final long sessionVersion;
    private final OAuth2User providerUser;

    OAuthAccountPrincipal(UUID accountId, long sessionVersion, OAuth2User providerUser) {
        this.accountId = Objects.requireNonNull(accountId);
        this.sessionVersion = sessionVersion;
        this.providerUser = Objects.requireNonNull(providerUser);
    }

    @Override
    public UUID accountId() {
        return accountId;
    }

    @Override
    public long sessionVersion() {
        return sessionVersion;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return providerUser.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AUTHORITIES;
    }

    @Override
    public String getName() {
        return accountId.toString();
    }

}
