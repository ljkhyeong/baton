package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.watch.DisabledWatchMonitorClient;
import com.personal.baton.adapter.out.external.watch.RestClientWatchMonitorClient;
import com.personal.baton.application.watch.WatchMonitorSource;
import com.personal.baton.application.watch.port.out.WatchMonitorClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WatchIntegrationProperties.class)
public class WatchIntegrationConfig {

    @Bean
    WatchMonitorSource watchMonitorSource(WatchIntegrationProperties properties) {
        return new WatchMonitorSource(
                properties.runtimeSourceNamespace(),
                properties.enabled(),
                properties.monitoringEnabled()
        );
    }

    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.watch", name = "enabled")
    WatchMonitorClient enabledWatchMonitorClient(
            WatchIntegrationProperties properties,
            RestClientWatchMonitorClient.Factory clientFactory
    ) {
        Duration connectTimeout = properties.requiredConnectTimeout();
        Duration readTimeout = properties.requiredReadTimeout();
        properties.validateRequestTimeoutBudget(connectTimeout, readTimeout);
        return clientFactory.create(
                properties.requiredBaseUri(),
                properties.requiredBearerToken(),
                connectTimeout,
                readTimeout
        );
    }

    @Bean
    @ConditionalOnBooleanProperty(
            prefix = "baton.watch",
            name = "enabled",
            havingValue = false,
            matchIfMissing = true
    )
    WatchMonitorClient disabledWatchMonitorClient() {
        return new DisabledWatchMonitorClient();
    }
}
