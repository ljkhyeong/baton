package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.http.ExternalHttpOrigin;
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
        return ExternalHttpOrigin.requireHttps("BRIEF service API 기본 URL", baseUrl);
    }

    String requiredBearerToken() {
        return OutboundHttpSettings.requireBearerToken("BRIEF service API", bearerToken);
    }

    Duration requiredConnectTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "BRIEF service API",
                "연결 시간 제한",
                connectTimeout
        );
    }

    Duration requiredReadTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "BRIEF service API",
                "읽기 시간 제한",
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
