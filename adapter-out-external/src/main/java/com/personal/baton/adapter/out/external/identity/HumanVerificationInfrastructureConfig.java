package com.personal.baton.adapter.out.external.identity;

import com.personal.baton.application.identity.port.out.HumanVerificationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TurnstileProperties.class)
public class HumanVerificationInfrastructureConfig {

    @Bean
    @ConditionalOnProperty(
            prefix = "baton.auth.turnstile",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    HumanVerificationPort disabledHumanVerificationPort() {
        return new DisabledHumanVerificationAdapter();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "baton.auth.turnstile",
            name = "enabled",
            havingValue = "true"
    )
    HumanVerificationPort turnstileHumanVerificationPort(
            TurnstileHumanVerificationAdapter.Factory factory,
            TurnstileProperties properties
    ) {
        return factory.create(properties);
    }
}
