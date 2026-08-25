package com.personal.baton.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton.calendar")
public record CalendarIntegrationProperties(boolean captureEnabled) {
}
