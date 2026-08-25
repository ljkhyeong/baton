package com.personal.baton.bootstrap.config;

import com.personal.baton.application.calendar.CalendarCaptureState;
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
}
