package com.personal.baton.adapter.in.web.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocialLoginConfigurationTest {

    private final SocialLoginConfiguration configuration =
            new SocialLoginConfiguration();

    @DisplayName("완전한 Google credential만 있으면 Google OIDC registration만 만든다")
    @Test
    void configuresOnlyAvailableGoogleRegistration() {
        SocialLoginProperties properties = enabledProperties();
        properties.getGoogle().setClientId("google-client");
        properties.getGoogle().setClientSecret("google-secret");

        ClientRegistrationRepository repository =
                configuration.batonClientRegistrationRepository(properties);

        ClientRegistration google = repository.findByRegistrationId("google");
        assertThat(google).isNotNull();
        assertThat(google.getScopes()).containsExactlyInAnyOrder(
                "openid",
                "profile",
                "email"
        );
        assertThat(repository.findByRegistrationId("naver")).isNull();
        assertThat(configuration.boundedOidcIdTokenDecoderFactory().createDecoder(google))
                .isInstanceOf(NimbusJwtDecoder.class);
    }

    @DisplayName("완전한 Naver credential은 OAuth2 profile endpoint와 client_secret_post로 등록한다")
    @Test
    void configuresNaverOAuth2Registration() {
        SocialLoginProperties properties = enabledProperties();
        properties.getNaver().setClientId("naver-client");
        properties.getNaver().setClientSecret("naver-secret");

        ClientRegistrationRepository repository =
                configuration.batonClientRegistrationRepository(properties);

        ClientRegistration naver = repository.findByRegistrationId("naver");
        assertThat(naver).isNotNull();
        assertThat(naver.getClientAuthenticationMethod())
                .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        assertThat(naver.getProviderDetails().getUserInfoEndpoint().getUri())
                .isEqualTo("https://openapi.naver.com/v1/nid/me");
        assertThat(naver.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName())
                .isEqualTo("response");
        assertThat(repository.findByRegistrationId("google")).isNull();
    }

    @DisplayName("provider credential 한쪽만 있으면 조용히 노출하지 않고 시작을 중단한다")
    @Test
    void failsFastOnPartialProviderCredential() {
        SocialLoginProperties properties = enabledProperties();
        properties.getGoogle().setClientId("google-client");

        assertThatThrownBy(() ->
                configuration.batonClientRegistrationRepository(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("함께 구성");
    }

    @DisplayName("production session cookie는 Secure HttpOnly SameSite=Lax host-only 경계를 사용한다")
    @Test
    void hardensProductionSessionCookie() {
        Session session = new Session();

        ProductionBrowserSessionConfiguration.secureSessionCookie(session);

        assertThat(session.getCookie().getSecure()).isTrue();
        assertThat(session.getCookie().getHttpOnly()).isTrue();
        assertThat(session.getCookie().getSameSite()).isEqualTo(Cookie.SameSite.LAX);
        assertThat(session.getCookie().getPath()).isEqualTo("/");
        assertThat(session.getCookie().getDomain()).isNull();
    }

    private SocialLoginProperties enabledProperties() {
        SocialLoginProperties properties = new SocialLoginProperties();
        properties.setEnabled(true);
        return properties;
    }
}
