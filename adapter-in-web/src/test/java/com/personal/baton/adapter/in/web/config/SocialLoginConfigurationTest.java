package com.personal.baton.adapter.in.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
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

}
