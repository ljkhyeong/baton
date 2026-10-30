package com.personal.baton.bootstrap.config;

import com.personal.baton.adapter.out.external.http.ExternalHttpOrigin;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.util.StringUtils;

@ConfigurationProperties("baton.brief.service-api")
public record BriefServiceApiProperties(
        boolean enabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String bearerToken,
        @DefaultValue("") String sslBundle,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout
) {

    URI requiredBaseUri() {
        return ExternalHttpOrigin.requireHttps("BRIEF service API 기본 URL", baseUrl);
    }

    String requiredBearerToken() {
        return OutboundHttpSettings.requireBearerToken("BRIEF service API", bearerToken);
    }

    /** 번들 이름을 지정하고 정의하지 않았으면 예외로 기동을 막는다. 미지정은 JVM 기본 신뢰 저장소를 뜻한다. */
    SslBundle configuredSslBundle(SslBundles sslBundles) {
        return StringUtils.hasText(sslBundle) ? sslBundles.getBundle(sslBundle) : null;
    }

    void validateTimeouts() {
        OutboundHttpSettings.validateTimeouts("BRIEF service API", connectTimeout, readTimeout);
    }

    @Override
    public String toString() {
        return "BriefServiceApiProperties[enabled=" + enabled
                + ", baseUrl=<redacted>, bearerToken=<redacted>, sslBundle=" + sslBundle
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
