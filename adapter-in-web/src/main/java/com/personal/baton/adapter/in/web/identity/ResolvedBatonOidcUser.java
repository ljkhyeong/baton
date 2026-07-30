package com.personal.baton.adapter.in.web.identity;

import java.util.UUID;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Authentication-time OIDC principal. It is replaced with {@link BatonAccountPrincipal}
 * before the authenticated security context is persisted to the HTTP session.
 */
public interface ResolvedBatonOidcUser extends OidcUser {

    UUID accountId();
}
