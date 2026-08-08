package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.in.web.config.AuthFeatureProperties;
import com.personal.baton.adapter.out.external.identity.IdentityEmailVerificationProperties;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class LocalRegistrationInfrastructureValidator implements SmartInitializingSingleton {

    private static final int AES_256_KEY_BYTES = 32;

    private final AuthFeatureProperties authProperties;
    private final IdentityEmailVerificationProperties emailProperties;
    private final Environment environment;

    LocalRegistrationInfrastructureValidator(
            AuthFeatureProperties authProperties,
            IdentityEmailVerificationProperties emailProperties,
            Environment environment
    ) {
        this.authProperties = authProperties;
        this.emailProperties = emailProperties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (isProductionProfile()) {
            requireAes256Key(emailProperties.outboxEncryptionKey());
        }
        if (!authProperties.localRegistrationEnabled()) {
            return;
        }
        if (emailProperties.delivery()
                != IdentityEmailVerificationProperties.Delivery.SMTP) {
            throw invalid("SMTP delivery가 활성화되어야 합니다");
        }
        emailProperties.requiredPublicOrigin();
        emailProperties.requiredFromAddress();
        requireAes256Key(emailProperties.outboxEncryptionKey());
        requireText("spring.mail.host");
        requirePort("spring.mail.port");
        requireText("spring.mail.username");
        requireText("spring.mail.password");
        requireTrue("spring.mail.properties.mail.smtp.auth");
        requireTrue("spring.mail.properties.mail.smtp.starttls.enable");
        requireTrue("spring.mail.properties.mail.smtp.starttls.required");
        requireTrue("spring.mail.properties.mail.smtp.ssl.checkserveridentity");
        requirePositiveInteger("spring.mail.properties.mail.smtp.connectiontimeout");
        requirePositiveInteger("spring.mail.properties.mail.smtp.timeout");
        requirePositiveInteger("spring.mail.properties.mail.smtp.writetimeout");
    }

    private boolean isProductionProfile() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch("production"::equals);
    }

    private void requireAes256Key(String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(requiredValue(
                    base64Key,
                    "outbox AES-256 key"
            ).trim());
        } catch (IllegalArgumentException exception) {
            throw invalid("outbox 암호화 키는 Base64여야 합니다");
        }
        try {
            if (decoded.length != AES_256_KEY_BYTES) {
                throw invalid("outbox 암호화 키는 32바이트여야 합니다");
            }
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private void requireText(String propertyName) {
        requiredValue(environment.getProperty(propertyName), propertyName);
    }

    private void requirePort(String propertyName) {
        int port = requireInteger(propertyName);
        if (port < 1 || port > 65_535) {
            throw invalid(propertyName + "는 1..65535 범위여야 합니다");
        }
    }

    private void requirePositiveInteger(String propertyName) {
        if (requireInteger(propertyName) < 1) {
            throw invalid(propertyName + "는 양수여야 합니다");
        }
    }

    private int requireInteger(String propertyName) {
        String value = requiredValue(environment.getProperty(propertyName), propertyName);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalid(propertyName + "는 정수여야 합니다");
        }
    }

    private void requireTrue(String propertyName) {
        String value = requiredValue(environment.getProperty(propertyName), propertyName);
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
