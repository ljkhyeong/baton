package com.personal.baton.adapter.in.web.auth;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

public final class OAuthAccountPrincipal implements
        AuthenticatedAccountPrincipal,
        OidcUser {

    private static final List<GrantedAuthority> AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT"));

    private final UUID accountId;
    private final Map<String, Object> claims;

    OAuthAccountPrincipal(UUID accountId) {
        this.accountId = accountId;
        this.claims = Map.of("account_id", accountId.toString());
    }

    @Override
    public UUID accountId() {
        return accountId;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return claims;
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
        return claims;
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return null;
    }

    @Override
    public OidcIdToken getIdToken() {
        return null;
    }
}
