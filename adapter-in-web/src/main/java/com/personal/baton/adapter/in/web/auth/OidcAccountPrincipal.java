package com.personal.baton.adapter.in.web.auth;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class OidcAccountPrincipal implements
        AuthenticatedAccountPrincipal,
        OidcUser {

    private static final List<GrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT"));

    private final UUID accountId;
    private final OidcUser providerUser;

    OidcAccountPrincipal(UUID accountId, OidcUser providerUser) {
        this.accountId = Objects.requireNonNull(accountId);
        this.providerUser = Objects.requireNonNull(providerUser);
        Objects.requireNonNull(providerUser.getIdToken(), "providerUser.idToken");
    }

    @Override
    public UUID accountId() {
        return accountId;
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

    @Override
    public Map<String, Object> getClaims() {
        return providerUser.getClaims();
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return providerUser.getUserInfo();
    }

    @Override
    public OidcIdToken getIdToken() {
        return providerUser.getIdToken();
    }
}
