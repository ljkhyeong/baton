package com.personal.baton.adapter.in.web.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton.auth.oauth2")
public class SocialLoginProperties {

    private static final List<String> SUPPORTED_PROVIDER_IDS = List.of(
            "google",
            "naver"
    );

    private boolean enabled;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> supportedProviderIds() {
        return SUPPORTED_PROVIDER_IDS;
    }

    public boolean supports(String providerId) {
        return SUPPORTED_PROVIDER_IDS.contains(providerId);
    }
}
