package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.http.ExternalHttpOrigin;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.watch")
public record WatchIntegrationProperties(
        boolean enabled,
        @DefaultValue("true") boolean monitoringEnabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String bearerToken,
        @DefaultValue("") String sourceNamespace,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout
) {

    private static final String DISABLED_SOURCE_NAMESPACE = "primary";

    String runtimeSourceNamespace() {
        if (!enabled && sourceNamespace.isBlank()) {
            return DISABLED_SOURCE_NAMESPACE;
        }
        if (sourceNamespace.isBlank()) {
            throw new IllegalStateException("WATCH 연동을 켤 때 원본 네임스페이스는 필수입니다");
        }
        return sourceNamespace;
    }

    URI requiredBaseUri() {
        return ExternalHttpOrigin.requireHttps("WATCH 기본 URL", baseUrl);
    }

    String requiredBearerToken() {
        return OutboundHttpSettings.requireBearerToken("WATCH", bearerToken);
    }

    Duration requiredConnectTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "WATCH",
                "연결 시간 제한",
                connectTimeout
        );
    }

    Duration requiredReadTimeout() {
        return OutboundHttpSettings.requirePositiveTimeout(
                "WATCH",
                "읽기 시간 제한",
                readTimeout
        );
    }

    void validateRequestTimeoutBudget(Duration connect, Duration read) {
        OutboundHttpSettings.validateRequestTimeoutBudget("WATCH", connect, read);
    }

    @Override
    public String toString() {
        return "WatchIntegrationProperties[enabled=" + enabled
                + ", monitoringEnabled=" + monitoringEnabled
                + ", baseUrl=<redacted>, bearerToken=<redacted>, sourceNamespace="
                + sourceNamespace + ", connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
