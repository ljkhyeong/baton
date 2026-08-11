package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.in.web.config.AuthFeatureProperties;
import com.personal.baton.adapter.out.external.identity.IdentityEmailVerificationProperties;
import com.personal.baton.adapter.out.external.identity.IdentityEmailVerificationProperties.Delivery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LocalRegistrationInfrastructureValidatorTest {

    private static final String TEST_KEY =
            "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=";

    @DisplayName("공개 자체 이메일 가입이 꺼져 있으면 SMTP와 암호화 키 없이도 앱을 시작한다")
    @Test
    void allowsMissingInfrastructureWhenRegistrationIsDisabled() {
        var validator = validator(
                false,
                new IdentityEmailVerificationProperties(Delivery.DISABLED, "", "", ""),
                new MailProperties(),
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
                new MailProperties(),
                environment
        ).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES-256 key");
    }

    @DisplayName("개발 환경도 공개 자체 이메일 가입을 켜면 outbox 복호화 키를 요구한다")
    @Test
    void enabledRegistrationRequiresOutboxKeyOutsideProduction() {
        assertThatThrownBy(() -> validator(
                true,
                new IdentityEmailVerificationProperties(
                        Delivery.SMTP,
                        "https://manager.b4ton.com",
                        "no-reply@b4ton.com",
                        ""
                ),
                secureMailProperties(),
                new MockEnvironment()
        ).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AES-256 key");
    }

    @DisplayName("production에서 공개 가입을 켜도 outbox 복호화 키는 한 번만 확인한다")
    @Test
    void validatesOutboxKeyOnceWhenProductionRegistrationIsEnabled() {
        IdentityEmailVerificationProperties properties =
                mock(IdentityEmailVerificationProperties.class);
        when(properties.delivery()).thenReturn(Delivery.SMTP);
        when(properties.outboxEncryptionKey()).thenReturn(TEST_KEY);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");

        validator(true, properties, secureMailProperties(), environment)
                .afterSingletonsInstantiated();

        verify(properties).outboxEncryptionKey();
    }

    @DisplayName("공개 자체 이메일 가입은 SMTP delivery를 요구한다")
    @Test
    void rejectsDisabledDelivery() {
        assertThatThrownBy(() -> validator(
                true,
                new IdentityEmailVerificationProperties(Delivery.DISABLED, "", "", ""),
                secureMailProperties(),
                new MockEnvironment()
        ).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SMTP delivery");
    }

    @DisplayName("공개 자체 이메일 가입은 인증·STARTTLS·hostname 검증과 timeout을 요구한다")
    @Test
    void rejectsInsecureMailProperties() {
        MailProperties mailProperties = secureMailProperties();
        mailProperties.getProperties().put("mail.smtp.starttls.required", "false");

        assertThatThrownBy(() -> validator(
                true,
                smtpProperties(),
                mailProperties,
                new MockEnvironment()
        )
                .afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("starttls.required");
    }

    @DisplayName("SMTP와 AES-256-GCM 필수값이 모두 준비되면 공개 가입 설정을 허용한다")
    @Test
    void acceptsCompleteInfrastructure() {
        var validator = validator(
                true,
                smtpProperties(),
                secureMailProperties(),
                new MockEnvironment()
        );

        assertThatCode(validator::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    private LocalRegistrationInfrastructureValidator validator(
            boolean localRegistrationEnabled,
            IdentityEmailVerificationProperties properties,
            MailProperties mailProperties,
            MockEnvironment environment
    ) {
        return new LocalRegistrationInfrastructureValidator(
                new AuthFeatureProperties(localRegistrationEnabled),
                properties,
                mailProperties,
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

    private MailProperties secureMailProperties() {
        MailProperties properties = new MailProperties();
        properties.setHost("smtp.example.com");
        properties.setPort(587);
        properties.setUsername("smtp-user");
        properties.setPassword("smtp-secret");
        properties.getProperties().put("mail.smtp.auth", "true");
        properties.getProperties().put("mail.smtp.starttls.enable", "true");
        properties.getProperties().put("mail.smtp.starttls.required", "true");
        properties.getProperties().put("mail.smtp.ssl.checkserveridentity", "true");
        properties.getProperties().put("mail.smtp.connectiontimeout", "5000");
        properties.getProperties().put("mail.smtp.timeout", "10000");
        properties.getProperties().put("mail.smtp.writetimeout", "10000");
        return properties;
    }
}
