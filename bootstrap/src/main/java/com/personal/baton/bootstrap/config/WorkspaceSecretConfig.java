package com.personal.baton.bootstrap.config;

import com.personal.baton.application.workspace.WorkspaceSecrets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkspaceSecretProperties.class)
public class WorkspaceSecretConfig {

    @Bean
    WorkspaceSecrets workspaceSecrets(WorkspaceSecretProperties properties) {
        return new WorkspaceSecrets(properties.creationKey(), properties.recoveryKey());
    }
}
