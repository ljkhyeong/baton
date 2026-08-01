package com.personal.baton.bootstrap.config;

import com.personal.baton.application.identity.IdentityInvitationSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityInvitationProperties.class)
public class IdentityInvitationConfig {

    @Bean
    IdentityInvitationSettings identityInvitationSettings(
            IdentityInvitationProperties properties
    ) {
        return new IdentityInvitationSettings(
                properties.bootstrapKey(),
                properties.invitationHmacSecret(),
                properties.bootstrapInvitationTtl(),
                properties.memberInvitationTtl()
        );
    }
}
