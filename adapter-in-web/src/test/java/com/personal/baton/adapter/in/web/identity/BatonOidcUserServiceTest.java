package com.personal.baton.adapter.in.web.identity;

import com.personal.baton.application.identity.port.in.OidcIdentityUseCase;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.OidcAccountResult;
import com.personal.baton.application.identity.port.in.OidcIdentityUseCase.VerifiedOidcIdentity;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BatonOidcUserServiceTest {

    private static final UUID ACCOUNT_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final Instant ISSUED_AT =
            Instant.parse("2026-07-30T12:00:00Z");

    @DisplayName("검증된 ID token의 issuer와 subject만 내부 계정 해석에 사용한다")
    @Test
    void resolvesBatonAccountFromValidatedIdToken() {
        OidcIdentityUseCase useCase = mock(OidcIdentityUseCase.class);
        when(useCase.resolveAccount(new VerifiedOidcIdentity(
                "https://accounts.google.com",
                "provider-subject"
        ))).thenReturn(new OidcAccountResult(ACCOUNT_ID, ISSUED_AT));
        BatonOidcUserService service = new BatonOidcUserService(useCase);

        ResolvedBatonOidcUser user = (ResolvedBatonOidcUser) service.loadUser(
                userRequest(idToken("https://accounts.google.com", "provider-subject"))
        );

        assertThat(user.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(user.getName()).isEqualTo(ACCOUNT_ID.toString());
        verify(useCase).resolveAccount(new VerifiedOidcIdentity(
                "https://accounts.google.com",
                "provider-subject"
        ));
    }

    @DisplayName("ID token의 issuer가 없으면 application 계정 해석을 호출하지 않는다")
    @Test
    void rejectsIdTokenWithoutIssuer() {
        OidcIdentityUseCase useCase = mock(OidcIdentityUseCase.class);
        BatonOidcUserService service = new BatonOidcUserService(useCase);

        assertThatThrownBy(() -> service.loadUser(
                userRequest(idToken(null, "provider-subject"))
        ))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(exception -> ((OAuth2AuthenticationException) exception)
                        .getError().getErrorCode())
                .isEqualTo("oidc_account_resolution_failed");
        verifyNoInteractions(useCase);
    }

    @DisplayName("내부 계정 해석 실패는 공급자 상세를 노출하지 않는 OIDC 인증 실패로 바꾼다")
    @Test
    void translatesAccountResolutionFailure() {
        OidcIdentityUseCase useCase = mock(OidcIdentityUseCase.class);
        when(useCase.resolveAccount(new VerifiedOidcIdentity(
                "https://accounts.google.com",
                "provider-subject"
        ))).thenThrow(new IllegalStateException("database detail"));
        BatonOidcUserService service = new BatonOidcUserService(useCase);

        assertThatThrownBy(() -> service.loadUser(
                userRequest(idToken(
                        "https://accounts.google.com",
                        "provider-subject"
                ))
        ))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining("OIDC 로그인 신원을 BATON 계정으로 확인하지 못했습니다")
                .hasMessageNotContaining("database detail");
    }

    private OidcUserRequest userRequest(OidcIdToken idToken) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "provider-access-token",
                ISSUED_AT,
                ISSUED_AT.plusSeconds(300),
                Set.of("openid")
        );
        return new OidcUserRequest(clientRegistration(), accessToken, idToken);
    }

    private OidcIdToken idToken(String issuer, String subject) {
        OidcIdToken.Builder builder = OidcIdToken.withTokenValue(
                        "provider-id-token")
                .issuedAt(ISSUED_AT)
                .expiresAt(ISSUED_AT.plusSeconds(300))
                .subject(subject);
        if (issuer != null) {
            builder.issuer(issuer);
        }
        return builder.build();
    }

    private ClientRegistration clientRegistration() {
        return ClientRegistration.withRegistrationId("google")
                .clientId("client-id")
                .clientSecret("client-secret")
                .clientAuthenticationMethod(
                        ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/api/v1/auth/oidc/callback/{registrationId}")
                .scope("openid")
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .tokenUri("https://oauth2.googleapis.com/token")
                .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
                .issuerUri("https://accounts.google.com")
                .userNameAttributeName("sub")
                .clientName("Google")
                .build();
    }
}
