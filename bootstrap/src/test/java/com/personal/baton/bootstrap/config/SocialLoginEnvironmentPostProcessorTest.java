package com.personal.baton.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

class SocialLoginEnvironmentPostProcessorTest {

    private final SocialLoginEnvironmentPostProcessor postProcessor =
            new SocialLoginEnvironmentPostProcessor();

    @DisplayName("기존 BATON env와 configtree credential을 Boot 표준 Google/Naver 속성으로 옮긴다")
    @Test
    void mapsLegacyCredentialsToStandardBootProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("baton.auth.oauth2.enabled", "true")
                .withProperty("baton.auth.oauth2.google.client-id", "google-client")
                .withProperty("baton.auth.oauth2.google.client-secret", "google-secret")
                .withProperty("baton.auth.oauth2.naver.client-id", "naver-client")
                .withProperty("baton.auth.oauth2.naver.client-secret", "naver-secret");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.client-id"
        )).isEqualTo("google-client");
        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.scope"
        )).isEqualTo("openid,profile,email");
        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.naver.client-secret"
        )).isEqualTo("naver-secret");
        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.naver.provider"
        )).isEqualTo("naver");
        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.naver.client-authentication-method"
        )).isEqualTo("client_secret_post");
    }

    @DisplayName("기존 BATON client ID env와 configtree secret은 표준 Google registration에 연결된다")
    @Test
    void mapsExistingEnvironmentVariableContract() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "testEnvironment",
                Map.of(
                        "BATON_AUTH_OAUTH2_ENABLED", "true",
                        "BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID", "google-client"
                )
        ));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "testConfigTree",
                Map.of("baton.auth.oauth2.google.client-secret", "google-secret")
        ));

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.client-id"
        )).isEqualTo("google-client");
        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.client-secret"
        )).isEqualTo("google-secret");
    }

    @DisplayName("호환 adapter는 표준 Boot credential을 덮지 않고 필요한 provider 기본값만 보충한다")
    @Test
    void keepsExplicitStandardRegistration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("baton.auth.oauth2.enabled", "true")
                .withProperty(
                        "spring.security.oauth2.client.registration.google.client-id",
                        "standard-client"
                )
                .withProperty(
                        "spring.security.oauth2.client.registration.google.client-secret",
                        "standard-secret"
                )
                .withProperty("baton.auth.oauth2.google.client-id", "legacy-client")
                .withProperty("baton.auth.oauth2.google.client-secret", "legacy-secret");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.client-id"
        )).isEqualTo("standard-client");
        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.scope"
        )).isEqualTo("openid,profile,email");
    }

    @DisplayName("완전한 표준 credential은 오래된 불완전 BATON 값보다 우선하고 기본값을 보충한다")
    @Test
    void prioritizesCompleteStandardRegistrationOverPartialLegacyConfiguration() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("baton.auth.oauth2.enabled", "true")
                .withProperty(
                        "spring.security.oauth2.client.registration.google.client-id",
                        "standard-client"
                )
                .withProperty(
                        "spring.security.oauth2.client.registration.google.client-secret",
                        "standard-secret"
                )
                .withProperty("baton.auth.oauth2.google.client-id", "stale-client");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.client-id"
        )).isEqualTo("standard-client");
        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.client-secret"
        )).isEqualTo("standard-secret");
        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.scope"
        )).isEqualTo("openid,profile,email");
        assertThat(environment.getProperty(
                "spring.security.oauth2.client.registration.google.client-name"
        )).isEqualTo("Google");
    }

    @DisplayName("gate가 열렸지만 credential 한쪽만 있으면 Boot context 전에 시작을 중단한다")
    @Test
    void rejectsPartialCredentialPair() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("baton.auth.oauth2.enabled", "true")
                .withProperty("baton.auth.oauth2.google.client-id", "google-client");

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-id와 client-secret은 함께 구성");
    }

    @DisplayName("gate가 열렸지만 지원 provider credential이 없으면 시작을 중단한다")
    @Test
    void rejectsMissingCredentials() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("baton.auth.oauth2.enabled", "true");

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("완전한 Google/Naver credential이 없습니다");
    }

}
