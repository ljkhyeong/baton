package com.personal.baton.adapter.in.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SocialLoginConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class,
                    RestTemplateAutoConfiguration.class,
                    OAuth2ClientAutoConfiguration.class
            ))
            .withUserConfiguration(SocialLoginConfiguration.class);

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
