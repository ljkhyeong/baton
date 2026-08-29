package com.personal.baton.bootstrap.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.calendar")
public record CalendarIntegrationProperties(
        boolean captureEnabled,
        boolean backfillEnabled,
        boolean deliveryEnabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String bearerToken,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout
) {

    URI requiredBaseUri() {
        return OutboundHttpSettings.requireHttpsOrigin("CAL", baseUrl);
    }

    String requiredBearerToken() {
        return OutboundHttpSettings.requireBearerToken("CAL", bearerToken);
    }

    Duration requiredConnectTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "CAL",
                "connect timeout",
                connectTimeout
        );
    }

    Duration requiredReadTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "CAL",
                "read timeout",
                readTimeout
        );
    }

    void validateRequestTimeoutBudget(Duration connect, Duration read) {
        OutboundHttpSettings.validateRequestTimeoutBudget("CAL", connect, read);
    }

    @Override
    public String toString() {
        return "CalendarIntegrationProperties[captureEnabled=" + captureEnabled
                + ", backfillEnabled=" + backfillEnabled
                + ", deliveryEnabled=" + deliveryEnabled
                + ", baseUrl=<redacted>, bearerToken=<redacted>, connectTimeout="
                + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
