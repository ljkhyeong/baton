package com.personal.baton.adapter.in.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

class SocialLoginBootBindingIntegrationTest {

    @TempDir
    private Path tempDirectory;

    @DisplayName("OAuth2 프로필은 configtree 비밀값으로 Google과 Naver 등록을 만든다")
    @Test
    void bindsProviderRegistrationsFromProfileAndConfigTree() throws IOException {
        Path configTree = Files.createDirectory(tempDirectory.resolve("enabled-config"));
        Files.writeString(
                configTree.resolve("baton.auth.oauth2.google.client-secret"),
                "google-secret"
        );
        Files.writeString(
                configTree.resolve("baton.auth.oauth2.naver.client-secret"),
                "naver-secret"
        );

        try (ConfigurableApplicationContext context = runContext(
                configTree,
                "--spring.profiles.active=oauth2",
                "--BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID=google-client",
                "--BATON_AUTH_OAUTH2_NAVER_CLIENT_ID=naver-client"
        )) {
            assertThat(context.getBean(ClientRegistrationRepository.class)).isNotNull();
            SocialLoginProviderCatalog catalog = context.getBean(
                    SocialLoginProviderCatalog.class
            );
            assertThat(catalog.availableProviderIds()).containsExactly("google", "naver");
            assertThat(catalog.registrations()
                    .findByRegistrationId("google")
                    .getClientSecret()).isEqualTo("google-secret");
            assertThat(catalog.registrations()
                    .findByRegistrationId("naver")
                    .getClientAuthenticationMethod())
                    .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        }
    }

    @DisplayName("OAuth2 프로필이 없으면 기존 자격 증명이 있어도 등록을 만들지 않는다")
    @Test
    void ignoresCredentialsWithoutOAuth2Profile() throws IOException {
        Path configTree = Files.createDirectory(tempDirectory.resolve("disabled-config"));
        Files.writeString(
                configTree.resolve("baton.auth.oauth2.google.client-secret"),
                "disabled-placeholder"
        );

        try (ConfigurableApplicationContext context = runContext(
                configTree,
                "--BATON_AUTH_OAUTH2_GOOGLE_CLIENT_ID=google-client"
        )) {
            assertThat(context.getBeansOfType(ClientRegistrationRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(SocialLoginProviderCatalog.class)).isEmpty();
        }
    }

    private ConfigurableApplicationContext runContext(
            Path configTree,
            String... additionalArguments
    ) {
        SpringApplication application = new SpringApplication(OAuthTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        String[] arguments = new String[additionalArguments.length + 2];
        arguments[0] = "--spring.main.banner-mode=off";
        arguments[1] = "--spring.config.import=configtree:" + configTree + "/";
        System.arraycopy(
                additionalArguments,
                0,
                arguments,
                2,
                additionalArguments.length
        );
        return application.run(arguments);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            RestClientAutoConfiguration.class,
            RestTemplateAutoConfiguration.class,
            OAuth2ClientAutoConfiguration.class
    })
    @Import(SocialLoginConfiguration.class)
    static class OAuthTestConfiguration {
    }
}
