package com.personal.baton.bootstrap.config;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;
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

    private static final Duration MAX_REQUEST_TIMEOUT_BUDGET = Duration.ofSeconds(45);
    private static final String DISABLED_SOURCE_NAMESPACE = "primary";
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z0-9._~-]{32,200}"
    );

    String runtimeSourceNamespace() {
        if (!enabled && sourceNamespace.isBlank()) {
            return DISABLED_SOURCE_NAMESPACE;
        }
        if (sourceNamespace.isBlank()) {
            throw new IllegalStateException("WATCH 연동을 켤 때 source namespace는 필수입니다");
        }
        return sourceNamespace;
    }

    URI requiredBaseUri() {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalStateException("WATCH base URL은 유효한 URI여야 합니다");
        }
        boolean secureScheme = "https".equalsIgnoreCase(uri.getScheme());
        boolean rootPath = uri.getPath() == null
                || uri.getPath().isEmpty()
                || "/".equals(uri.getPath());
        if (!secureScheme
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || !rootPath
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "WATCH base URL은 path, user info, query, fragment가 없는 절대 HTTPS origin이어야 합니다"
            );
        }
        validateExplicitPort(uri);
        return uri;
    }

    private void validateExplicitPort(URI uri) {
        String rawAuthority = uri.getRawAuthority();
        boolean portOmitted = rawAuthority.equalsIgnoreCase(uri.getHost());
        int port = uri.getPort();
        if (!portOmitted && (port < 1 || port > 65_535)) {
            throw new IllegalStateException("WATCH base URL의 명시 포트는 1~65535 범위여야 합니다");
        }
    }

    String requiredBearerToken() {
        if (!BEARER_TOKEN_PATTERN.matcher(bearerToken).matches()) {
            throw new IllegalStateException(
                    "WATCH bearer token은 32~200자의 URL-safe ASCII여야 합니다"
            );
        }
        return bearerToken;
    }

    Duration requiredConnectTimeout() {
        return requiredPositiveTimeout(connectTimeout, "connect timeout");
    }

    Duration requiredReadTimeout() {
        return requiredPositiveTimeout(readTimeout, "read timeout");
    }

    void validateRequestTimeoutBudget(Duration connect, Duration read) {
        if (connect.compareTo(MAX_REQUEST_TIMEOUT_BUDGET) >= 0
                || read.compareTo(MAX_REQUEST_TIMEOUT_BUDGET.minus(connect)) > 0) {
            throw new IllegalStateException(
                    "WATCH connect timeout과 read timeout의 합은 45초 이하여야 합니다"
            );
        }
    }

    private Duration requiredPositiveTimeout(Duration timeout, String name) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("WATCH " + name + "은 0보다 커야 합니다");
        }
        return timeout;
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
