package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.AccountView;
import com.personal.baton.application.identity.error.AccountDeactivatedException;
import com.personal.baton.application.identity.error.IdentityOperationUnavailableException;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase;
import com.personal.baton.application.identity.port.in.ResolveExternalLoginUseCase.ExternalLoginResult;
import com.personal.baton.domain.identity.IdentityProvider;
import java.util.List;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountOAuth2UserServiceTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("8e448211-66ae-44ab-9888-c4960648c22b");

    @DisplayName("Google OIDC sub로 계정을 resolve하고 callback 동안 provider OIDC 계약을 보존한다")
    @Test
    void resolvesGoogleByOidcSubject() {
        ResolveExternalLoginUseCase resolveUseCase = mock(ResolveExternalLoginUseCase.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate =
                mock(OAuth2UserService.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2Delegate =
                mock(OAuth2UserService.class);
        AccountOAuth2UserService service = service(
                resolveUseCase,
                oidcDelegate,
                oauth2Delegate
        );
        OidcUserRequest userRequest = mock(OidcUserRequest.class);
        OidcUser providerUser = mock(OidcUser.class);
        OidcIdToken idToken = mock(OidcIdToken.class);
        OidcUserInfo userInfo = mock(OidcUserInfo.class);
        Map<String, Object> providerClaims = Map.of(
                "sub", "google-subject-123",
                "email", "same@example.com"
        );
        when(userRequest.getClientRegistration()).thenReturn(googleRegistration());
        when(providerUser.getSubject()).thenReturn("google-subject-123");
        when(providerUser.getEmail()).thenReturn("same@example.com");
        when(providerUser.getEmailVerified()).thenReturn(true);
        when(providerUser.getFullName()).thenReturn("Google Member");
        when(providerUser.getAttributes()).thenReturn(providerClaims);
        when(providerUser.getClaims()).thenReturn(providerClaims);
        when(providerUser.getIdToken()).thenReturn(idToken);
        when(providerUser.getUserInfo()).thenReturn(userInfo);
        when(oidcDelegate.loadUser(userRequest)).thenReturn(providerUser);
        when(resolveUseCase.resolveExternalLogin(any())).thenReturn(
                new ExternalLoginResult(accountView(), true)
        );

        OidcUser principal = service.loadOidcUser(userRequest);

        assertThat(((AuthenticatedAccountPrincipal) principal).accountId())
                .isEqualTo(ACCOUNT_ID);
        assertThat(principal).isInstanceOf(OidcAccountPrincipal.class);
        assertThat(principal.getAttributes()).isSameAs(providerClaims);
        assertThat(principal.getClaims()).isSameAs(providerClaims);
        assertThat(principal.getIdToken()).isSameAs(idToken);
        assertThat(principal.getUserInfo()).isSameAs(userInfo);
        verify(resolveUseCase).resolveExternalLogin(
                new ResolveExternalLoginUseCase.ExternalLoginCommand(
                        IdentityProvider.GOOGLE,
                        "google-subject-123",
                        "same@example.com",
                        true,
                        "Google Member"
                )
        );
    }

    @DisplayName("Naver OAuth2 프로필의 중첩 response.id를 공급자 사용자 식별자로 사용한다")
    @Test
    void resolvesNaverByNestedResponseId() {
        ResolveExternalLoginUseCase resolveUseCase = mock(ResolveExternalLoginUseCase.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate =
                mock(OAuth2UserService.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2Delegate =
                mock(OAuth2UserService.class);
        AccountOAuth2UserService service = service(
                resolveUseCase,
                oidcDelegate,
                oauth2Delegate
        );
        OAuth2UserRequest userRequest = mock(OAuth2UserRequest.class);
        when(userRequest.getClientRegistration()).thenReturn(naverRegistration());
        Map<String, Object> response = Map.of(
                "id", "naver-profile-id-456",
                "email", "same@example.com",
                "nickname", "Naver Member"
        );
        OAuth2User providerUser = new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("response", response),
                "response"
        );
        when(oauth2Delegate.loadUser(userRequest)).thenReturn(providerUser);
        when(resolveUseCase.resolveExternalLogin(any())).thenReturn(
                new ExternalLoginResult(accountView(), true)
        );

        OAuth2User principal = service.loadOAuth2User(userRequest);

        assertThat(((AuthenticatedAccountPrincipal) principal).accountId())
                .isEqualTo(ACCOUNT_ID);
        assertThat(principal).isInstanceOf(OAuthAccountPrincipal.class);
        assertThat(principal.getAttributes())
                .containsExactly(Map.entry("response", response));
        verify(resolveUseCase).resolveExternalLogin(
                new ResolveExternalLoginUseCase.ExternalLoginCommand(
                        IdentityProvider.NAVER,
                        "naver-profile-id-456",
                        "same@example.com",
                        false,
                        "Naver Member"
                )
        );
    }

    @DisplayName("Google 표시 이름은 domain 길이 계약에 맞추되 surrogate pair를 자르지 않는다")
    @Test
    void safelyLimitsUnicodeDisplayName() {
        ResolveExternalLoginUseCase resolveUseCase = mock(ResolveExternalLoginUseCase.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate =
                mock(OAuth2UserService.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2Delegate =
                mock(OAuth2UserService.class);
        AccountOAuth2UserService service = service(
                resolveUseCase,
                oidcDelegate,
                oauth2Delegate
        );
        OidcUserRequest userRequest = mock(OidcUserRequest.class);
        OidcUser providerUser = mock(OidcUser.class);
        when(userRequest.getClientRegistration()).thenReturn(googleRegistration());
        when(providerUser.getSubject()).thenReturn("unicode-name-subject");
        when(providerUser.getFullName()).thenReturn("🙂".repeat(60));
        when(providerUser.getIdToken()).thenReturn(mock(OidcIdToken.class));
        when(oidcDelegate.loadUser(userRequest)).thenReturn(providerUser);
        when(resolveUseCase.resolveExternalLogin(any())).thenReturn(
                new ExternalLoginResult(accountView(), true)
        );

        service.loadOidcUser(userRequest);

        ArgumentCaptor<ResolveExternalLoginUseCase.ExternalLoginCommand> command =
                ArgumentCaptor.forClass(ResolveExternalLoginUseCase.ExternalLoginCommand.class);
        verify(resolveUseCase).resolveExternalLogin(command.capture());
        String displayName = command.getValue().displayName();
        assertThat(displayName).hasSize(100);
        assertThat(displayName.codePointCount(0, displayName.length())).isEqualTo(50);
        assertThat(Character.isLowSurrogate(displayName.charAt(99))).isTrue();
    }

    @DisplayName("Naver response.id가 없으면 일반화된 OAuth profile 오류로 fail-closed 한다")
    @Test
    void rejectsNaverProfileWithoutSubject() {
        ResolveExternalLoginUseCase resolveUseCase = mock(ResolveExternalLoginUseCase.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate =
                mock(OAuth2UserService.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2Delegate =
                mock(OAuth2UserService.class);
        AccountOAuth2UserService service = service(
                resolveUseCase,
                oidcDelegate,
                oauth2Delegate
        );
        OAuth2UserRequest userRequest = mock(OAuth2UserRequest.class);
        when(userRequest.getClientRegistration()).thenReturn(naverRegistration());
        OAuth2User providerUser = new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("response", Map.of("nickname", "No Subject")),
                "response"
        );
        when(oauth2Delegate.loadUser(userRequest)).thenReturn(providerUser);

        assertThatThrownBy(() -> service.loadOAuth2User(userRequest))
                .isInstanceOf(OAuth2AuthenticationException.class);
        verify(resolveUseCase, never()).resolveExternalLogin(any());
    }

    @DisplayName("Google identity 인프라 장애는 원인을 보존한 OAuth 인증 실패로 변환한다")
    @Test
    void wrapsGoogleIdentityInfrastructureFailureForSecurityFailureHandler() {
        ResolveExternalLoginUseCase resolveUseCase = mock(ResolveExternalLoginUseCase.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate =
                mock(OAuth2UserService.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2Delegate =
                mock(OAuth2UserService.class);
        AccountOAuth2UserService service = service(
                resolveUseCase,
                oidcDelegate,
                oauth2Delegate
        );
        OidcUserRequest userRequest = mock(OidcUserRequest.class);
        OidcUser providerUser = mock(OidcUser.class);
        IdentityOperationUnavailableException failure =
                new IdentityOperationUnavailableException(
                        "identity repository unavailable",
                        new IllegalStateException("test database failure")
                );
        when(userRequest.getClientRegistration()).thenReturn(googleRegistration());
        when(providerUser.getSubject()).thenReturn("google-subject-123");
        when(providerUser.getFullName()).thenReturn("Google Member");
        when(oidcDelegate.loadUser(userRequest)).thenReturn(providerUser);
        when(resolveUseCase.resolveExternalLogin(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.loadOidcUser(userRequest))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasCause(failure);
    }

    @DisplayName("identity와 무관한 외부 로그인 결함은 OAuth 실패로 오분류하지 않는다")
    @Test
    void preservesUnrelatedExternalLoginFailure() {
        ResolveExternalLoginUseCase resolveUseCase = mock(ResolveExternalLoginUseCase.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate =
                mock(OAuth2UserService.class);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2Delegate =
                mock(OAuth2UserService.class);
        AccountOAuth2UserService service = service(
                resolveUseCase,
                oidcDelegate,
                oauth2Delegate
        );
        OAuth2UserRequest userRequest = mock(OAuth2UserRequest.class);
        OAuth2User providerUser = new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("response", Map.of(
                        "id", "naver-profile-id-456",
                        "nickname", "Naver Member"
                )),
                "response"
        );
        IllegalStateException failure = new IllegalStateException("unexpected defect");
        when(userRequest.getClientRegistration()).thenReturn(naverRegistration());
        when(oauth2Delegate.loadUser(userRequest)).thenReturn(providerUser);
        when(resolveUseCase.resolveExternalLogin(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.loadOAuth2User(userRequest))
                .isSameAs(failure);
    }

    @Test @DisplayName("비활성 계정의 OAuth 로그인은 계정 상태 안내로 리디렉션한다")
    @SuppressWarnings("unchecked")
    void rejectsDeactivatedOAuthAccount() throws Exception {
        var resolve = mock(ResolveExternalLoginUseCase.class);
        OAuth2UserService<OidcUserRequest, OidcUser> oidc = mock(OAuth2UserService.class);
        OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth = mock(OAuth2UserService.class);
        var request = mock(OidcUserRequest.class);
        var user = mock(OidcUser.class);
        when(request.getClientRegistration()).thenReturn(googleRegistration());
        when(user.getSubject()).thenReturn("disabled-google-subject");
        when(oidc.loadUser(request)).thenReturn(user);
        when(resolve.resolveExternalLogin(any())).thenThrow(new AccountDeactivatedException());
        var service = service(resolve, oidc, oauth);
        var failure = assertThrows(OAuth2AuthenticationException.class, () -> service.loadOidcUser(request));
        assertThat(failure.getError().getErrorCode()).isEqualTo("account_deactivated");
        var response = new MockHttpServletResponse();
        new OAuthBrowserAuthenticationFailureHandler().onAuthenticationFailure(new MockHttpServletRequest(), response, failure);
        assertThat(response.getRedirectedUrl()).isEqualTo("/login?accountNotice=account_deactivated");
    }

    private AccountOAuth2UserService service(
            ResolveExternalLoginUseCase resolveUseCase,
            OAuth2UserService<OidcUserRequest, OidcUser> oidcDelegate,
            OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2Delegate
    ) {
        return new AccountOAuth2UserService(
                resolveUseCase,
                oidcDelegate,
                oauth2Delegate
        );
    }

    private AccountView accountView() {
        return new AccountView(ACCOUNT_ID, "Member", List.of(), 0);
    }

    private ClientRegistration googleRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("google-client")
                .clientSecret("google-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
    }

    private ClientRegistration naverRegistration() {
        return ClientRegistration.withRegistrationId("naver")
                .clientId("naver-client")
                .clientSecret("naver-secret")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("https://nid.naver.com/oauth2.0/authorize")
                .tokenUri("https://nid.naver.com/oauth2.0/token")
                .userInfoUri("https://openapi.naver.com/v1/nid/me")
                .userNameAttributeName("response")
                .clientName("Naver")
                .build();
    }
}
