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

    String configuredBearerToken() {
        if (bearerToken.isBlank()) {
            return null;
        }
        return OutboundHttpSettings.requireBearerToken("BRIEF", bearerToken);
    }

    void validateTimeouts() {
        OutboundHttpSettings.validateTimeouts("BRIEF", connectTimeout, readTimeout);
    }

    @Override
    public String toString() {
        return "BriefIntegrationProperties[deliveryEnabled=" + deliveryEnabled
                + ", baseUrl=<redacted>, bearerToken=<redacted>, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
