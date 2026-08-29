package com.personal.baton.bootstrap.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.brief.service-api")
public record BriefServiceApiProperties(
        boolean enabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String bearerToken,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout
) {

    URI requiredBaseUri() {
        return OutboundHttpSettings.requireHttpsOrigin("BRIEF service API", baseUrl);
    }

    String requiredBearerToken() {
        return OutboundHttpSettings.requireBearerToken("BRIEF service API", bearerToken);
    }

    Duration requiredConnectTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "BRIEF service API",
                "connect timeout",
                connectTimeout
        );
    }

    Duration requiredReadTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "BRIEF service API",
                "read timeout",
                readTimeout
        );
    }

    void validateRequestTimeoutBudget(Duration connect, Duration read) {
        OutboundHttpSettings.validateRequestTimeoutBudget(
                "BRIEF service API",
                connect,
                read
        );
    }

    @Override
    public String toString() {
        return "BriefServiceApiProperties[enabled=" + enabled
                + ", baseUrl=<redacted>, bearerToken=<redacted>, connectTimeout="
                + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
