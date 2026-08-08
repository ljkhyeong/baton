package com.personal.baton.adapter.in.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton.auth.oauth2")
public class SocialLoginProperties {

    private boolean enabled;
    private final ProviderCredentials google = new ProviderCredentials();
    private final ProviderCredentials naver = new ProviderCredentials();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ProviderCredentials getGoogle() {
        return google;
    }

    public ProviderCredentials getNaver() {
        return naver;
    }

    public static final class ProviderCredentials {

        private String clientId;
        private String clientSecret;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }
}
