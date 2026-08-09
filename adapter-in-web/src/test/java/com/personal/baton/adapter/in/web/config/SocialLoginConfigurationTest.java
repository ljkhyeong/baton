package com.personal.baton.adapter.in.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.server.Cookie;
import org.springframework.boot.web.server.servlet.Session;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

class SocialLoginConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class,
                    RestTemplateAutoConfiguration.class,
                    OAuth2ClientAutoConfiguration.class
            ))
            .withUserConfiguration(SocialLoginConfiguration.class);

    @DisplayName("표준 Google registration은 Boot repository와 관리형 OAuth client를 구성한다")
    @Test
    void configuresGoogleWithBootManagedInfrastructure() {
        contextRunner
                .withPropertyValues(
                        "baton.auth.oauth2.enabled=true",
                        "spring.security.oauth2.client.registration.google.client-id=google-client",
                        "spring.security.oauth2.client.registration.google.client-secret=google-secret",
                        "spring.security.oauth2.client.registration.google.scope=openid,profile,email"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ClientRegistrationRepository.class);
                    assertThat(context).hasSingleBean(SocialLoginProviderCatalog.class);
                    assertThat(context).hasSingleBean(DefaultOAuth2UserService.class);
                    assertThat(context).hasSingleBean(OidcUserService.class);
                    assertThat(context).hasSingleBean(
                            RestClientAuthorizationCodeTokenResponseClient.class
                    );
                    assertThat(context).hasSingleBean(JwtDecoderFactory.class);

                    SocialLoginProviderCatalog catalog = context.getBean(
                            SocialLoginProviderCatalog.class
                    );
                    assertThat(catalog.availableProviderIds()).containsExactly("google");
                    ClientRegistration google = catalog.registrations()
                            .findByRegistrationId("google");
                    assertThat(google).isNotNull();
                    assertThat(google.getScopes()).containsExactlyInAnyOrder(
                            "openid",
                            "profile",
                            "email"
                    );
                    JwtDecoderFactory<ClientRegistration> decoderFactory = context.getBean(
                            JwtDecoderFactory.class
                    );
                    assertThat(decoderFactory.createDecoder(google))
                            .isInstanceOf(NimbusJwtDecoder.class);
                });
    }

    @DisplayName("표준 Naver registration은 YAML provider endpoint와 client_secret_post를 사용한다")
    @Test
    void configuresNaverProviderDetails() {
        contextRunner
                .withPropertyValues(
                        "baton.auth.oauth2.enabled=true",
                        "spring.security.oauth2.client.registration.naver.client-id=naver-client",
                        "spring.security.oauth2.client.registration.naver.client-secret=naver-secret",
                        "spring.security.oauth2.client.registration.naver.provider=naver",
                        "spring.security.oauth2.client.registration.naver.client-authentication-method=client_secret_post",
                        "spring.security.oauth2.client.registration.naver.authorization-grant-type=authorization_code",
                        "spring.security.oauth2.client.registration.naver.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
                        "spring.security.oauth2.client.provider.naver.authorization-uri=https://nid.naver.com/oauth2.0/authorize",
                        "spring.security.oauth2.client.provider.naver.token-uri=https://nid.naver.com/oauth2.0/token",
                        "spring.security.oauth2.client.provider.naver.user-info-uri=https://openapi.naver.com/v1/nid/me",
                        "spring.security.oauth2.client.provider.naver.user-name-attribute=response"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    SocialLoginProviderCatalog catalog = context.getBean(
                            SocialLoginProviderCatalog.class
                    );
                    assertThat(catalog.availableProviderIds()).containsExactly("naver");
                    ClientRegistration naver = catalog.registrations()
                            .findByRegistrationId("naver");
                    assertThat(naver).isNotNull();
                    assertThat(naver.getClientAuthenticationMethod())
                            .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
                    assertThat(naver.getProviderDetails().getUserInfoEndpoint().getUri())
                            .isEqualTo("https://openapi.naver.com/v1/nid/me");
                    assertThat(naver.getProviderDetails()
                            .getUserInfoEndpoint()
                            .getUserNameAttributeName()).isEqualTo("response");
                });
    }

    @DisplayName("gate가 닫히고 registration key가 없으면 OAuth repository와 client를 만들지 않는다")
    @Test
    void keepsOAuthInfrastructureDisabledWithoutRegistrationKeys() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ClientRegistrationRepository.class);
            assertThat(context).doesNotHaveBean(SocialLoginProviderCatalog.class);
            assertThat(context).doesNotHaveBean(DefaultOAuth2UserService.class);
            assertThat(context).doesNotHaveBean(
                    RestClientAuthorizationCodeTokenResponseClient.class
            );
        });
    }

    @DisplayName("지원하지 않는 표준 registration은 allowlist 검증에서 시작을 중단한다")
    @Test
    void rejectsUnsupportedRegistration() {
        contextRunner
                .withPropertyValues(
                        "baton.auth.oauth2.enabled=true",
                        "spring.security.oauth2.client.registration.github.client-id=github-client",
                        "spring.security.oauth2.client.registration.github.client-secret=github-secret"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "지원하지 않는 OAuth2 registration입니다: github"
                            );
                });
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
}
