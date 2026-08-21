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

    @DisplayName("configtree의 기존 Google secret은 gate가 열릴 때 Boot registration을 만든다")
    @Test
    void mapsConfigTreeSecretWhenGateIsEnabled() throws IOException {
        Path configTree = Files.createDirectory(tempDirectory.resolve("enabled-config"));
        Files.writeString(
                configTree.resolve("baton.auth.oauth2.google.client-secret"),
                "google-secret"
        );

        try (ConfigurableApplicationContext context = runContext(
                configTree,
                "--baton.auth.oauth2.enabled=true",
                "--baton.auth.oauth2.google.client-id=google-client"
        )) {
            assertThat(context.getBean(ClientRegistrationRepository.class)).isNotNull();
            SocialLoginProviderCatalog catalog = context.getBean(
                    SocialLoginProviderCatalog.class
            );
            assertThat(catalog.availableProviderIds()).containsExactly("google");
            assertThat(catalog.registrations()
                    .findByRegistrationId("google")
                    .getClientSecret()).isEqualTo("google-secret");
        }
    }

    @DisplayName("기존 Google client ID와 configtree secret이 모두 있어도 닫힌 gate는 registration을 만들지 않는다")
    @Test
    void ignoresStaticConfigTreeSecretWhenDisabled() throws IOException {
        Path configTree = Files.createDirectory(tempDirectory.resolve("disabled-config"));
        Files.writeString(
                configTree.resolve("baton.auth.oauth2.google.client-secret"),
                "disabled-placeholder"
        );

        try (ConfigurableApplicationContext context = runContext(
                configTree,
                "--baton.auth.oauth2.enabled=false",
                "--baton.auth.oauth2.google.client-id=google-client"
        )) {
            assertThat(context.getBeansOfType(ClientRegistrationRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(SocialLoginProviderCatalog.class)).isEmpty();
        }
    }

    @DisplayName("표준 Google/Naver credential만 직접 주면 Boot가 두 registration을 생성한다")
    @Test
    void supportsDirectStandardGoogleAndNaverConfiguration() throws IOException {
        Path configTree = Files.createDirectory(tempDirectory.resolve("standard-config"));

        try (ConfigurableApplicationContext context = runContext(
                configTree,
                "--baton.auth.oauth2.enabled=true",
                "--spring.security.oauth2.client.registration.google.client-id=google-client",
                "--spring.security.oauth2.client.registration.google.client-secret=google-secret",
                "--spring.security.oauth2.client.registration.naver.client-id=naver-client",
                "--spring.security.oauth2.client.registration.naver.client-secret=naver-secret"
        )) {
            SocialLoginProviderCatalog catalog = context.getBean(
                    SocialLoginProviderCatalog.class
            );
            assertThat(catalog.availableProviderIds()).containsExactly("google", "naver");
            assertThat(catalog.registrations()
                    .findByRegistrationId("naver")
                    .getClientAuthenticationMethod())
                    .isEqualTo(ClientAuthenticationMethod.CLIENT_SECRET_POST);
            assertThat(catalog.registrations()
                    .findByRegistrationId("naver")
                    .getProviderDetails()
                    .getUserInfoEndpoint()
                    .getUserNameAttributeName()).isEqualTo("response");
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
