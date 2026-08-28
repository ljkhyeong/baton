package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.brief.RestClientBriefContinuityClient;
import com.personal.baton.application.brief.BriefContinuityOutboxDispatchService;
import com.personal.baton.application.brief.port.in.DispatchBriefContinuityOutboxUseCase;
import com.personal.baton.application.brief.port.out.BriefContinuityClient;
import com.personal.baton.application.brief.port.out.BriefContinuityOutboxPort;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BriefIntegrationProperties.class)
public class BriefIntegrationConfig {

    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.brief", name = "delivery-enabled")
    BriefContinuityClient briefContinuityClient(
            BriefIntegrationProperties properties,
            RestClientBriefContinuityClient.Factory clientFactory
    ) {
        Duration connectTimeout = properties.requiredConnectTimeout();
        Duration readTimeout = properties.requiredReadTimeout();
        properties.validateRequestTimeoutBudget(connectTimeout, readTimeout);
        return clientFactory.create(
                properties.requiredBaseUri(),
                properties.configuredBearerToken(),
                connectTimeout,
                readTimeout
        );
    }

    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.brief", name = "delivery-enabled")
    DispatchBriefContinuityOutboxUseCase dispatchBriefContinuityOutboxUseCase(
            BriefContinuityOutboxPort outboxPort,
            BriefContinuityClient client,
            Clock clock
    ) {
        return new BriefContinuityOutboxDispatchService(outboxPort, client, clock);
    }
}
