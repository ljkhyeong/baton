package com.personal.baton.bootstrap.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * 기존 BATON 환경 변수와 구성 트리 이름을 표준 Spring Boot OAuth2 속성으로 연결한다.
 * 등록 구성과 검증은 {@code spring.security.oauth2.client} 바인딩과 자동 설정이 소유한다.
 */
public final class SocialLoginEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "batonSocialLoginCompatibility";
    private static final String BATON_PREFIX = "baton.auth.oauth2.";
    private static final String SPRING_PREFIX =
            "spring.security.oauth2.client.registration.";

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment environment,
            SpringApplication application
    ) {
        if (!environment.getProperty(
                BATON_PREFIX + "enabled",
                Boolean.class,
                false
        )) {
            return;
        }

        Map<String, Object> standardProperties = new LinkedHashMap<>();
        boolean googleConfigured = mapProviderCredentials(
                environment,
                standardProperties,
                "google",
                "Google"
        );
        boolean naverConfigured = mapProviderCredentials(
                environment,
                standardProperties,
                "naver",
                "Naver"
        );
        if (!googleConfigured && !naverConfigured) {
            throw new IllegalStateException(
                    "OAuth2 로그인이 활성화됐지만 완전한 Google/Naver credential이 없습니다"
            );
        }

        if (googleConfigured) {
            addGoogleRegistrationDefaults(environment, standardProperties);
        }
        if (naverConfigured) {
            addNaverRegistrationDefaults(environment, standardProperties);
        }
        if (!standardProperties.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource(PROPERTY_SOURCE_NAME, standardProperties)
            );
        }
    }

    private boolean mapProviderCredentials(
            ConfigurableEnvironment environment,
            Map<String, Object> standardProperties,
            String providerId,
            String providerName
    ) {
        String standardPrefix = SPRING_PREFIX + providerId + ".";
        String standardClientId = environment.getProperty(standardPrefix + "client-id");
        String standardClientSecret = environment.getProperty(
                standardPrefix + "client-secret"
        );
        boolean standardConfigured = validatePair(
                providerName,
                "spring.security.oauth2.client.registration",
                standardClientId,
                standardClientSecret
        );
        if (standardConfigured) {
            return true;
        }

        String batonPrefix = BATON_PREFIX + providerId + ".";
        String batonClientId = environment.getProperty(batonPrefix + "client-id");
        String batonClientSecret = environment.getProperty(batonPrefix + "client-secret");
        boolean batonConfigured = validatePair(
                providerName,
                "BATON OAuth2",
                batonClientId,
                batonClientSecret
        );

        if (!batonConfigured) {
            return false;
        }
        standardProperties.put(standardPrefix + "client-id", batonClientId);
        standardProperties.put(standardPrefix + "client-secret", batonClientSecret);
        return true;
    }

    private boolean validatePair(
            String providerName,
            String propertySource,
            String clientId,
            String clientSecret
    ) {
        boolean clientIdPresent = StringUtils.hasText(clientId);
        boolean clientSecretPresent = StringUtils.hasText(clientSecret);
        if (clientIdPresent != clientSecretPresent) {
            throw new IllegalStateException(
                    providerName + " " + propertySource
                            + " client-id와 client-secret은 함께 구성해야 합니다"
            );
        }
        return clientIdPresent;
    }

    private void addGoogleRegistrationDefaults(
            ConfigurableEnvironment environment,
            Map<String, Object> properties
    ) {
        String prefix = SPRING_PREFIX + "google.";
        putDefault(environment, properties, prefix + "scope", "openid,profile,email");
        putDefault(environment, properties, prefix + "client-name", "Google");
    }

    private void addNaverRegistrationDefaults(
            ConfigurableEnvironment environment,
            Map<String, Object> properties
    ) {
        String prefix = SPRING_PREFIX + "naver.";
        putDefault(environment, properties, prefix + "provider", "naver");
        putDefault(
                environment,
                properties,
                prefix + "client-authentication-method",
                "client_secret_post"
        );
        putDefault(
                environment,
                properties,
                prefix + "authorization-grant-type",
                "authorization_code"
        );
        putDefault(
                environment,
                properties,
                prefix + "redirect-uri",
                "{baseUrl}/login/oauth2/code/{registrationId}"
        );
        putDefault(environment, properties, prefix + "client-name", "Naver");
    }

    private void putDefault(
            ConfigurableEnvironment environment,
            Map<String, Object> properties,
            String propertyName,
            String defaultValue
    ) {
        if (environment.getProperty(propertyName) == null) {
            properties.put(propertyName, defaultValue);
        }
    }
}
