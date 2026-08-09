package com.personal.baton.adapter.in.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountSessionSecurityContextRepositoryTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");

    @DisplayName("최초 session 저장 전에 OIDC 인증을 account UUID와 권한만 남도록 정규화한다")
    @Test
    void canonicalizesOidcAuthenticationBeforeDelegatingSave() {
        OidcUser providerUser = mock(OidcUser.class);
        when(providerUser.getIdToken()).thenReturn(mock(OidcIdToken.class));
        OidcAccountPrincipal callbackPrincipal =
                new OidcAccountPrincipal(ACCOUNT_ID, providerUser);
        OAuth2AuthenticationToken callbackAuthentication = new OAuth2AuthenticationToken(
                callbackPrincipal,
                Set.of(
                        new SimpleGrantedAuthority("ROLE_ACCOUNT"),
                        new SimpleGrantedAuthority("SCOPE_provider_admin")
                ),
                "google"
        );
        SecurityContext callbackContext = SecurityContextHolder.createEmptyContext();
        callbackContext.setAuthentication(callbackAuthentication);
        AtomicReference<Authentication> authenticationAtDelegate = new AtomicReference<>();
        SecurityContextRepository delegate = recordingDelegate(authenticationAtDelegate);
        AccountSessionSecurityContextRepository repository =
                new AccountSessionSecurityContextRepository(delegate);

        repository.saveContext(
                callbackContext,
                new MockHttpServletRequest(),
                new MockHttpServletResponse()
        );

        Authentication persistedAuthentication = authenticationAtDelegate.get();
        assertThat(persistedAuthentication)
                .isNotNull()
                .isNotInstanceOf(OAuth2AuthenticationToken.class)
                .isSameAs(callbackContext.getAuthentication());
        assertThat(persistedAuthentication.getPrincipal())
                .isInstanceOf(AccountSessionPrincipal.class)
                .isInstanceOf(Serializable.class)
                .isNotInstanceOf(OidcUser.class);
        AccountSessionPrincipal sessionPrincipal =
                (AccountSessionPrincipal) persistedAuthentication.getPrincipal();
        assertThat(sessionPrincipal.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(persistedAuthentication.getCredentials()).isNull();
        assertThat(persistedAuthentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_ACCOUNT");
    }

    @DisplayName("BATON 계정 principal이 아닌 OAuth 성공 인증은 저장하지 않고 fail-closed 한다")
    @Test
    void rejectsRawProviderAuthenticationBeforeDelegatingSave() {
        OidcUser rawProviderUser = mock(OidcUser.class);
        OAuth2AuthenticationToken providerAuthentication = new OAuth2AuthenticationToken(
                rawProviderUser,
                Set.of(new SimpleGrantedAuthority("OIDC_USER")),
                "google"
        );
        SecurityContext providerContext = SecurityContextHolder.createEmptyContext();
        providerContext.setAuthentication(providerAuthentication);
        AtomicReference<Authentication> authenticationAtDelegate = new AtomicReference<>();
        SecurityContextRepository delegate = recordingDelegate(authenticationAtDelegate);
        AccountSessionSecurityContextRepository repository =
                new AccountSessionSecurityContextRepository(delegate);

        assertThatThrownBy(() -> repository.saveContext(
                providerContext,
                new MockHttpServletRequest(),
                new MockHttpServletResponse()
        )).isInstanceOf(IllegalStateException.class);

        assertThat(authenticationAtDelegate.get()).isNull();
        assertThat(providerContext.getAuthentication()).isNull();
    }

    private SecurityContextRepository recordingDelegate(
            AtomicReference<Authentication> authenticationAtDelegate
    ) {
        return new SecurityContextRepository() {
            @SuppressWarnings("deprecation")
            @Override
            public SecurityContext loadContext(
                    HttpRequestResponseHolder holder
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void saveContext(
                    SecurityContext context,
                    HttpServletRequest request,
                    HttpServletResponse response
            ) {
                authenticationAtDelegate.set(context.getAuthentication());
            }

            @Override
            public boolean containsContext(HttpServletRequest request) {
                return false;
            }
        };
    }
}
