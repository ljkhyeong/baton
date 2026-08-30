package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.calendar.DisabledCalendarClient;
import com.personal.baton.adapter.out.external.calendar.RestClientCalendarClient;
import com.personal.baton.application.calendar.CalendarCaptureState;
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
        properties.validateSeasonMetadataMaintenance();
        return new CalendarCaptureState(properties.captureEnabled(), properties.seasonMetadataEnabled());
    }

    @Bean
    @ConditionalOnBooleanProperty(prefix = "baton.calendar", name = "delivery-enabled")
    RestClientCalendarClient enabledCalendarSnapshotClient(
            CalendarIntegrationProperties properties,
            RestClientCalendarClient.Factory clientFactory
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
    DisabledCalendarClient disabledCalendarSnapshotClient() {
        return new DisabledCalendarClient();
    }
}
