package com.personal.baton.adapter.in.web.identity;

import com.personal.baton.application.identity.port.in.OidcIdentityUseCase;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.OidcAccountResult;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.VerifiedOidcIdentity;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

@Component
public class BatonOidcUserService
        implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final String ACCOUNT_RESOLUTION_ERROR =
            "oidc_account_resolution_failed";

    private final OidcIdentityUseCase oidcIdentityUseCase;

    public BatonOidcUserService(OidcIdentityUseCase oidcIdentityUseCase) {
        this.oidcIdentityUseCase = oidcIdentityUseCase;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest)
            throws OAuth2AuthenticationException {
        OidcIdToken idToken = Objects.requireNonNull(
                userRequest,
                "OIDC user request is required"
        ).getIdToken();
        URL issuer = idToken.getIssuer();
        String subject = idToken.getSubject();
        if (issuer == null || subject == null || subject.isBlank()) {
            throw accountResolutionFailed(null);
        }

        try {
            OidcAccountResult account = oidcIdentityUseCase.resolveAccount(
                    new VerifiedOidcIdentity(issuer.toExternalForm(), subject)
            );
            return new AuthenticationTimeOidcUser(account.accountId(), idToken);
        } catch (RuntimeException exception) {
            throw accountResolutionFailed(exception);
        }
    }

    private OAuth2AuthenticationException accountResolutionFailed(
            RuntimeException cause
    ) {
        OAuth2Error error = new OAuth2Error(
                ACCOUNT_RESOLUTION_ERROR,
                "OIDC 로그인 신원을 BATON 계정으로 확인하지 못했습니다",
                null
        );
        return cause == null
                ? new OAuth2AuthenticationException(error)
                : new OAuth2AuthenticationException(
                        error,
                        error.getDescription(),
                        cause
                );
    }

    private static final class AuthenticationTimeOidcUser
            implements ResolvedBatonOidcUser {

        private static final Collection<GrantedAuthority> AUTHORITIES =
                List.of(new SimpleGrantedAuthority("ROLE_USER"));

        private final UUID accountId;
        private final OidcIdToken idToken;

        private AuthenticationTimeOidcUser(
                UUID accountId,
                OidcIdToken idToken
        ) {
            this.accountId = Objects.requireNonNull(accountId);
            this.idToken = Objects.requireNonNull(idToken);
        }

        @Override
        public UUID accountId() {
            return accountId;
        }

        @Override
        public Map<String, Object> getClaims() {
            return idToken.getClaims();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return getClaims();
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
        public OidcUserInfo getUserInfo() {
            return null;
        }

        @Override
        public OidcIdToken getIdToken() {
            return idToken;
        }
    }
}
