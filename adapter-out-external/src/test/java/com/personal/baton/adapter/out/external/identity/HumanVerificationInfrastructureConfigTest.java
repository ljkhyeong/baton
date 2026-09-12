package com.personal.baton.adapter.out.external.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.baton.application.identity.port.out.HumanVerificationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration;
import org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HumanVerificationInfrastructureConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(HumanVerificationInfrastructureConfig.class,
                    TurnstileHumanVerificationAdapter.Factory.class)
            .withConfiguration(AutoConfigurations.of(HttpClientAutoConfiguration.class,
                    ImperativeHttpClientAutoConfiguration.class, RestClientAutoConfiguration.class));

    @Test
    @DisplayName("키가 없는 기본 설정은 외부 호출 없이 기존 요청을 허용한다")
    void startsDisabled() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(HumanVerificationPort.class);
            var port = context.getBean(HumanVerificationPort.class);
            assertThat(port.siteKey()).isEmpty();
            assertThat(port.verify(new HumanVerificationPort.HumanVerificationAttempt(null, null, "local_registration")))
                    .isEqualTo(HumanVerificationPort.VerificationOutcome.VERIFIED);
        });
    }

    @Test
    @DisplayName("검증을 켜면 실제 클라이언트를 조립하고 공개 키만 노출한다")
    void startsEnabled() {
        runner.withPropertyValues("baton.auth.turnstile.enabled=true",
                "baton.auth.turnstile.site-key=public-key", "baton.auth.turnstile.secret-key=private-key",
                "baton.auth.turnstile.expected-hostname=b4ton.com").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(HumanVerificationPort.class);
            assertThat(context.getBean(HumanVerificationPort.class)).isInstanceOf(TurnstileHumanVerificationAdapter.class);
            assertThat(context.getBean(HumanVerificationPort.class).siteKey()).contains("public-key");
        });
    }

    @Test
    @DisplayName("활성 설정에서 비밀 키가 빠지면 시작을 거부한다")
    void rejectsMissingSecret() {
        runner.withPropertyValues("baton.auth.turnstile.enabled=true",
                "baton.auth.turnstile.site-key=public-key",
                "baton.auth.turnstile.expected-hostname=b4ton.com").run(context ->
                assertThat(context).hasFailed());
    }
}
