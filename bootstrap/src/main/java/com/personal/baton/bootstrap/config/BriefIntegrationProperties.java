package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.http.ExternalHttpOrigin;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.brief")
public record BriefIntegrationProperties(
        boolean deliveryEnabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String bearerToken,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout
) {

    URI requiredBaseUri() {
        return ExternalHttpOrigin.requireHttpsOrLoopbackHttp("BRIEF 기본 URL", baseUrl);
    }

    Duration requiredConnectTimeout() {
        return requiredPositiveTimeout(connectTimeout, "연결 시간 제한");
    }

    String configuredBearerToken() {
        if (bearerToken.isBlank()) {
            return null;
        }
        return OutboundHttpSettings.requireBearerToken("BRIEF", bearerToken);
    }

    Duration requiredReadTimeout() {
        return requiredPositiveTimeout(readTimeout, "읽기 시간 제한");
    }

    void validateRequestTimeoutBudget(Duration connect, Duration read) {
        OutboundHttpSettings.validateRequestTimeoutBudget("BRIEF", connect, read);
    }

    private Duration requiredPositiveTimeout(Duration timeout, String name) {
        return OutboundHttpSettings.requirePositiveTimeout("BRIEF", name, timeout);
    }

    @Override
    public String toString() {
        return "BriefIntegrationProperties[deliveryEnabled=" + deliveryEnabled
                + ", baseUrl=<redacted>, bearerToken=<redacted>, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
