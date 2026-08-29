package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.brief.BriefRestClientFactory;
import com.personal.baton.adapter.out.external.brief.DisabledBriefEditionServiceClient;
import com.personal.baton.application.brief.BriefEditionApplicationService;
import com.personal.baton.application.brief.port.in.BriefEditionUseCase;
import com.personal.baton.application.brief.port.out.BriefEditionGenerationExecutionPort;
import com.personal.baton.application.brief.port.out.BriefEditionServiceClient;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.port.in.VerifyWorkspaceAccessUseCase;
import com.personal.baton.application.workspace.port.out.WorkspaceRepository;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BriefServiceApiProperties.class)
public class BriefServiceApiConfig {

    @Bean
    BriefEditionServiceClient briefEditionServiceClient(
            BriefServiceApiProperties properties,
            BriefRestClientFactory clientFactory
    ) {
        if (!properties.enabled()) {
            return DisabledBriefEditionServiceClient.INSTANCE;
        }

        Duration connectTimeout = properties.requiredConnectTimeout();
        Duration readTimeout = properties.requiredReadTimeout();
        properties.validateRequestTimeoutBudget(connectTimeout, readTimeout);
        return clientFactory.createEditionServiceClient(
                properties.requiredBaseUri(),
                properties.requiredBearerToken(),
                connectTimeout,
                readTimeout
        );
    }

    @Bean
    BriefEditionUseCase briefEditionUseCase(
            VerifyWorkspaceAccessUseCase workspaceAccess,
            WorkspaceRepository workspaceRepository,
            RoundAuthorizationRepository roundAuthorizationRepository,
            BriefEditionServiceClient client,
            BriefEditionGenerationExecutionPort executionPort,
            Clock clock
    ) {
        return new BriefEditionApplicationService(
                workspaceAccess,
                workspaceRepository,
                roundAuthorizationRepository,
                client,
                executionPort,
                clock
        );
    }
}
