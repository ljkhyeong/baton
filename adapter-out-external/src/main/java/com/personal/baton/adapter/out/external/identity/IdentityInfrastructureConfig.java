package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.port.out.EmailVerificationDeliveryPort;
import com.personal.baton.application.identity.port.out.EmailVerificationOutboxPayloadProtector;
import com.personal.baton.application.identity.port.out.PasswordHashingPort;
import com.personal.baton.application.identity.port.out.SecureTokenGeneratorPort;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailSender;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityEmailVerificationProperties.class)
public class IdentityInfrastructureConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        String currentId = "pbkdf2@SpringSecurity_v5_8";
        return new DelegatingPasswordEncoder(
                currentId,
                Map.of(
                        currentId,
                        Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                        "bcrypt",
                        new BCryptPasswordEncoder()
                )
        );
    }

    @Bean
    PasswordHashingPort passwordHashingPort(PasswordEncoder passwordEncoder) {
        return new SpringPasswordHashingAdapter(passwordEncoder);
    }

    @Bean
    SecureTokenGeneratorPort secureTokenGeneratorPort() {
        return new SecureRandomTokenGenerator();
    }

    @Bean
    EmailVerificationOutboxPayloadProtector emailVerificationOutboxPayloadProtector(
            IdentityEmailVerificationProperties properties
    ) {
        if (properties.outboxEncryptionKey().isBlank()) {
            return new DisabledEmailVerificationOutboxPayloadProtector();
        }
        return new AesGcmEmailVerificationOutboxPayloadProtector(
                properties.outboxEncryptionKey()
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "baton.identity.email-verification",
            name = "delivery",
            havingValue = "disabled",
            matchIfMissing = true
    )
    EmailVerificationDeliveryPort disabledEmailVerificationDeliveryPort() {
        return new DisabledEmailVerificationDeliveryAdapter();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "baton.identity.email-verification",
            name = "delivery",
            havingValue = "smtp"
    )
    EmailVerificationDeliveryPort smtpEmailVerificationDeliveryPort(
            MailSender mailSender,
            IdentityEmailVerificationProperties properties
    ) {
        return new SmtpEmailVerificationDeliveryAdapter(
                mailSender,
                properties.requiredFromAddress(),
                properties.requiredPublicOrigin()
        );
    }
}
