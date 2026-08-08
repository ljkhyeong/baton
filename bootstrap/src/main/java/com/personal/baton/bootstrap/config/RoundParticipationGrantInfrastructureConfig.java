package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.roundauth.DisabledParticipationGrantInfrastructure;
import com.personal.baton.adapter.out.external.roundauth.NimbusParticipationGrantInfrastructure;
import com.personal.baton.adapter.out.external.roundauth.NimbusParticipationGrantInfrastructure.PublicKeyMaterial;
import com.personal.baton.adapter.out.external.roundauth.NimbusParticipationGrantInfrastructure.SigningKeyMaterial;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RoundParticipationGrantProperties.class)
public class RoundParticipationGrantInfrastructureConfig {

    @Bean
    @ConditionalOnBooleanProperty(
            prefix = "baton.round.participation-grant",
            name = "enabled"
    )
    NimbusParticipationGrantInfrastructure enabledParticipationGrantInfrastructure(
            RoundParticipationGrantProperties properties
    ) {
        RoundParticipationGrantProperties.SigningKey currentKey = properties.currentKey();
        SigningKeyMaterial signingKey = currentKey == null
                ? null
                : new SigningKeyMaterial(
                        currentKey.kid(),
                        currentKey.privateKeyPath(),
                        currentKey.publicKeyPath()
                );
        List<PublicKeyMaterial> previousKeys = properties.configuredPreviousPublicKeys().stream()
                .map(key -> new PublicKeyMaterial(key.kid(), key.publicKeyPath()))
                .toList();
        return new NimbusParticipationGrantInfrastructure(
                properties.issuer(),
                properties.audience(),
                signingKey,
                previousKeys
        );
    }

    @Bean
    @ConditionalOnBooleanProperty(
            prefix = "baton.round.participation-grant",
            name = "enabled",
            havingValue = false,
            matchIfMissing = true
    )
    DisabledParticipationGrantInfrastructure disabledParticipationGrantInfrastructure() {
        return new DisabledParticipationGrantInfrastructure();
    }
}
