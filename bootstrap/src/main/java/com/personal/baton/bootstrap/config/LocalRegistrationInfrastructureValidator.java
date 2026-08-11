package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.in.web.config.AuthFeatureProperties;
import com.personal.baton.adapter.out.external.identity.IdentityEmailVerificationProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.mail.autoconfigure.MailProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

@Component
@EnableConfigurationProperties(MailProperties.class)
class LocalRegistrationInfrastructureValidator implements SmartInitializingSingleton {

    private final AuthFeatureProperties authProperties;
    private final IdentityEmailVerificationProperties emailProperties;
    private final MailProperties mailProperties;
    private final Environment environment;

    LocalRegistrationInfrastructureValidator(
            AuthFeatureProperties authProperties,
            IdentityEmailVerificationProperties emailProperties,
            MailProperties mailProperties,
            Environment environment
    ) {
        this.authProperties = authProperties;
        this.emailProperties = emailProperties;
        this.mailProperties = mailProperties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        boolean localRegistrationEnabled = authProperties.localRegistrationEnabled();
        if (!localRegistrationEnabled) {
            if (isProductionProfile()) {
                requiredValue(emailProperties.outboxEncryptionKey(), "outbox AES-256 key");
            }
            return;
        }
        if (emailProperties.delivery()
                != IdentityEmailVerificationProperties.Delivery.SMTP) {
            throw invalid("SMTP delivery가 활성화되어야 합니다");
        }
        requiredValue(emailProperties.outboxEncryptionKey(), "outbox AES-256 key");
        requiredValue(mailProperties.getHost(), "spring.mail.host");
        requirePort(mailProperties.getPort(), "spring.mail.port");
        requiredValue(mailProperties.getUsername(), "spring.mail.username");
        requiredValue(mailProperties.getPassword(), "spring.mail.password");
        requireTrue("mail.smtp.auth");
        requireTrue("mail.smtp.starttls.enable");
        requireTrue("mail.smtp.starttls.required");
        requireTrue("mail.smtp.ssl.checkserveridentity");
        requirePositiveInteger("mail.smtp.connectiontimeout");
        requirePositiveInteger("mail.smtp.timeout");
        requirePositiveInteger("mail.smtp.writetimeout");
    }

    private boolean isProductionProfile() {
        return environment.acceptsProfiles(Profiles.of("production"));
    }

    private void requirePort(Integer port, String propertyName) {
        if (port == null) {
            throw invalid(propertyName + "가 필요합니다");
        }
        if (port < 1 || port > 65_535) {
            throw invalid(propertyName + "는 1..65535 범위여야 합니다");
        }
    }

    private void requirePositiveInteger(String propertyName) {
        if (requireIntegerProperty(propertyName) < 1) {
            throw invalid(propertyName + "는 양수여야 합니다");
        }
    }

    private int requireIntegerProperty(String propertyName) {
        String value = requiredValue(mailProperties.getProperties().get(propertyName), propertyName);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid(propertyName + "는 정수여야 합니다");
        }
    }

    private void requireTrue(String propertyName) {
        String value = requiredValue(mailProperties.getProperties().get(propertyName), propertyName);
        if (!"true".equalsIgnoreCase(value)) {
            throw invalid(propertyName + "는 true여야 합니다");
        }
    }

    private String requiredValue(String value, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(label + "가 필요합니다");
        }
        return value;
    }

    private IllegalStateException invalid(String reason) {
        return new IllegalStateException("자체 이메일 공개 가입 설정이 불완전합니다: " + reason);
    }
}
