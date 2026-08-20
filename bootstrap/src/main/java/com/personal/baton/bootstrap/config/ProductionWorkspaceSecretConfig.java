package com.personal.baton.bootstrap.config;

import com.personal.baton.application.workspace.WorkspaceSecrets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Profile("production")
@Configuration(proxyBeanMethods = false)
public class ProductionWorkspaceSecretConfig {

    @Bean
    ProductionWorkspaceSecretGuard productionWorkspaceSecretGuard(
            WorkspaceSecrets workspaceSecrets
    ) {
        return new ProductionWorkspaceSecretGuard(workspaceSecrets);
    }

    static final class ProductionWorkspaceSecretGuard {

        private ProductionWorkspaceSecretGuard(WorkspaceSecrets workspaceSecrets) {
            String creationKey = workspaceSecrets.creationKey();
            String recoveryKey = workspaceSecrets.recoveryKey();
            requireConfigured(creationKey, "BATON_WORKSPACE_CREATION_KEY");
            requireConfigured(recoveryKey, "BATON_WORKSPACE_RECOVERY_KEY");
            if (creationKey.equals(recoveryKey)) {
                throw new IllegalStateException(
                        "production 프로필의 생성 키와 복구 키는 서로 달라야 합니다"
                );
            }
        }

        private void requireConfigured(String value, String environmentName) {
            if (value.isBlank()) {
                throw new IllegalStateException(
                        "production 프로필에는 " + environmentName + " 설정이 필요합니다"
                );
            }
        }
    }
}
