package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.port.out.HumanVerificationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TurnstileProperties.class)
public class HumanVerificationInfrastructureConfig {

    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.auth.turnstile", name = "enabled", havingValue = false, matchIfMissing = true)
    HumanVerificationPort disabledHumanVerificationPort() {
        return new DisabledHumanVerificationAdapter();
    }

    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.auth.turnstile", name = "enabled")
    HumanVerificationPort turnstileHumanVerificationPort(
            TurnstileHumanVerificationAdapter.Factory factory,
            TurnstileProperties properties
    ) {
        return factory.create(properties);
    }
}
