package com.personal.baton.bootstrap.config;

import com.personal.baton.application.identity.IdentityInvitationSettings;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("production")
@Configuration(proxyBeanMethods = false)
public class ProductionIdentitySecretConfig {

    private static final int MIN_SECRET_LENGTH = 32;

    @Bean
    ProductionIdentitySecretGuard productionIdentitySecretGuard(
            @Value("${baton.workspace.creation-key:}") String creationKey,
            @Value("${baton.workspace.recovery-key:}") String recoveryKey,
            IdentityInvitationSettings settings
    ) {
        return new ProductionIdentitySecretGuard(
                creationKey,
                recoveryKey,
                settings
        );
    }

    static final class ProductionIdentitySecretGuard {

        private ProductionIdentitySecretGuard(
                String creationKey,
                String recoveryKey,
                IdentityInvitationSettings settings
        ) {
            Map<String, String> secrets = new LinkedHashMap<>();
            secrets.put("BATON_WORKSPACE_CREATION_KEY", creationKey);
            secrets.put("BATON_WORKSPACE_RECOVERY_KEY", recoveryKey);
            secrets.put("BATON_IDENTITY_BOOTSTRAP_KEY", settings.bootstrapKey());
            secrets.put(
                    "BATON_IDENTITY_INVITATION_HMAC_SECRET",
                    settings.invitationHmacSecret()
            );
            secrets.forEach(this::requireConfigured);
            if (secrets.values().stream().distinct().count() != secrets.size()) {
                throw new IllegalStateException(
                        "production 프로필의 workspace와 identity 비밀값은 모두 서로 달라야 합니다"
                );
            }
            if (!Duration.ofHours(1).equals(settings.bootstrapInvitationTtl())) {
                throw new IllegalStateException(
                        "production 프로필의 BATON_IDENTITY_BOOTSTRAP_INVITATION_TTL은(는) PT1H여야 합니다"
                );
            }
            if (!Duration.ofHours(24).equals(settings.memberInvitationTtl())) {
                throw new IllegalStateException(
                        "production 프로필의 BATON_IDENTITY_MEMBER_INVITATION_TTL은(는) PT24H여야 합니다"
                );
            }
        }

        private void requireConfigured(String environmentName, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        "production 프로필에는 " + environmentName + " 설정이 필요합니다"
                );
            }
            if (value.length() < MIN_SECRET_LENGTH) {
                throw new IllegalStateException(
                        "production 프로필의 " + environmentName + "은(는) 최소 32자여야 합니다"
                );
            }
        }
    }
}
