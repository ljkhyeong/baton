package com.personal.baton.adapter.out.external.link;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("baton.integrations.go")
public class BatonGoProperties {

    private boolean enabled;
    private URI baseUrl;
    private URI publicBaseUrl;
    private String managementToken;
    private URI roundPublicBaseUrl;
    private Duration connectTimeout = Duration.ofMillis(500);
    private Duration readTimeout = Duration.ofSeconds(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    public URI getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(URI publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getManagementToken() {
        return managementToken;
    }

    public void setManagementToken(String managementToken) {
        this.managementToken = managementToken;
    }

    public URI getRoundPublicBaseUrl() {
        return roundPublicBaseUrl;
    }

    public void setRoundPublicBaseUrl(URI roundPublicBaseUrl) {
        this.roundPublicBaseUrl = roundPublicBaseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
