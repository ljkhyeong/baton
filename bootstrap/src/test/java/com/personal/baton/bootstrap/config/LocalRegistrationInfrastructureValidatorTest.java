package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.in.web.config.AuthFeatureProperties;
import com.personal.baton.adapter.out.external.identity.IdentityEmailVerificationProperties;
import com.personal.baton.adapter.out.external.identity.IdentityEmailVerificationProperties.Delivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalRegistrationInfrastructureValidatorTest {

    private static final String TEST_KEY =
            "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    @DisplayName("공개 자체 이메일 가입이 꺼져 있으면 SMTP와 암호화 키 없이도 앱을 시작한다")
    @Test
    void allowsMissingInfrastructureWhenRegistrationIsDisabled() {
        var validator = validator(
                false,
                new IdentityEmailVerificationProperties(Delivery.DISABLED, "", "", ""),
                new MockEnvironment()
        );

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @DisplayName("production은 공개 가입 gate와 무관하게 기존 outbox 복호화 키를 요구한다")
    @Test
    void productionAlwaysRequiresOutboxKey() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        assertThatThrownBy(() -> validator(
                false,
                new IdentityEmailVerificationProperties(Delivery.DISABLED, "", "", ""),
                environment
        ).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES-256 key");
    }

    @DisplayName("공개 자체 이메일 가입은 SMTP delivery와 AES-256 key를 모두 요구한다")
    @Test
    void rejectsDisabledDeliveryAndMissingKey() {
        assertThatThrownBy(() -> validator(
                true,
                new IdentityEmailVerificationProperties(Delivery.DISABLED, "", "", ""),
                new MockEnvironment()
        ).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP delivery");

        assertThatThrownBy(() -> validator(
                true,
                new IdentityEmailVerificationProperties(
                        Delivery.SMTP,
                        "https://manager.b4ton.com",
                        "no-reply@b4ton.com",
                        ""
                ),
                secureMailEnvironment()
        ).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES-256 key");
    }

    @DisplayName("공개 자체 이메일 가입은 인증·STARTTLS·hostname 검증과 timeout을 요구한다")
    @Test
    void rejectsInsecureMailProperties() {
        MockEnvironment environment = secureMailEnvironment()
                .withProperty("spring.mail.properties.mail.smtp.starttls.required", "false");

        assertThatThrownBy(() -> validator(true, smtpProperties(), environment)
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("starttls.required");
    }

    @DisplayName("SMTP와 AES-256-GCM 필수값이 모두 준비되면 공개 가입 설정을 허용한다")
    @Test
    void acceptsCompleteInfrastructure() {
        var validator = validator(true, smtpProperties(), secureMailEnvironment());

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    private LocalRegistrationInfrastructureValidator validator(
            boolean localRegistrationEnabled,
            IdentityEmailVerificationProperties properties,
            MockEnvironment environment
    ) {
        return new LocalRegistrationInfrastructureValidator(
                new AuthFeatureProperties(localRegistrationEnabled),
                properties,
                environment
        );
    }

    private IdentityEmailVerificationProperties smtpProperties() {
        return new IdentityEmailVerificationProperties(
                Delivery.SMTP,
                "https://manager.b4ton.com",
                "no-reply@b4ton.com",
                TEST_KEY
        );
    }

    private MockEnvironment secureMailEnvironment() {
        return new MockEnvironment()
                .withProperty("spring.mail.host", "smtp.example.com")
                .withProperty("spring.mail.port", "587")
                .withProperty("spring.mail.username", "smtp-user")
                .withProperty("spring.mail.password", "smtp-secret")
                .withProperty("spring.mail.properties.mail.smtp.auth", "true")
                .withProperty("spring.mail.properties.mail.smtp.starttls.enable", "true")
                .withProperty("spring.mail.properties.mail.smtp.starttls.required", "true")
                .withProperty("spring.mail.properties.mail.smtp.ssl.checkserveridentity", "true")
                .withProperty("spring.mail.properties.mail.smtp.connectiontimeout", "5000")
                .withProperty("spring.mail.properties.mail.smtp.timeout", "10000")
                .withProperty("spring.mail.properties.mail.smtp.writetimeout", "10000");
    }
}
