package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.calendar.DisabledCalendarSnapshotClient;
import com.personal.baton.adapter.out.external.calendar.RestClientCalendarSnapshotClient;
import com.personal.baton.application.calendar.CalendarCaptureState;
import com.personal.baton.application.calendar.port.out.CalendarSnapshotClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CalendarIntegrationProperties.class)
public class CalendarIntegrationConfig {

    @Bean
    CalendarCaptureState calendarCaptureState(CalendarIntegrationProperties properties) {
        return new CalendarCaptureState(properties.captureEnabled());
    }

    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.calendar", name = "delivery-enabled")
    CalendarSnapshotClient enabledCalendarSnapshotClient(
            CalendarIntegrationProperties properties,
            RestClientCalendarSnapshotClient.Factory clientFactory
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
            prefix = "baton.calendar",
            name = "delivery-enabled",
            havingValue = false,
            matchIfMissing = true
    )
    CalendarSnapshotClient disabledCalendarSnapshotClient() {
        return new DisabledCalendarSnapshotClient();
    }
}
