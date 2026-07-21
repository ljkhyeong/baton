package com.personal.baton.bootstrap.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("production")
@Configuration(proxyBeanMethods = false)
public class ProductionWorkspaceSecretConfig {

    private static final int MIN_SECRET_LENGTH = 32;

    @Bean
    ProductionWorkspaceSecretGuard productionWorkspaceSecretGuard(
            @Value("${baton.workspace.creation-key:}") String creationKey,
            @Value("${baton.workspace.recovery-key:}") String recoveryKey
    ) {
        return new ProductionWorkspaceSecretGuard(creationKey, recoveryKey);
    }

    static final class ProductionWorkspaceSecretGuard {

        private ProductionWorkspaceSecretGuard(String creationKey, String recoveryKey) {
            requireConfigured(creationKey, "BATON_WORKSPACE_CREATION_KEY");
            requireConfigured(recoveryKey, "BATON_WORKSPACE_RECOVERY_KEY");
            if (creationKey.equals(recoveryKey)) {
                throw new IllegalStateException(
                        "production 프로필의 생성 키와 복구 키는 서로 달라야 합니다"
                );
            }
        }

        private void requireConfigured(String value, String environmentName) {
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
