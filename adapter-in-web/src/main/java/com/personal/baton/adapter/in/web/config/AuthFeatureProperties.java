package com.personal.baton.adapter.in.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton.auth")
public record AuthFeatureProperties(boolean localRegistrationEnabled, boolean passwordResetEnabled) {
}
