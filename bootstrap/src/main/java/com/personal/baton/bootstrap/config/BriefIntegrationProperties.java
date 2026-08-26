package com.personal.baton.bootstrap.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.brief")
public record BriefIntegrationProperties(
        boolean deliveryEnabled,
        @DefaultValue("") String baseUrl,
        @DefaultValue("PT2S") Duration connectTimeout,
        @DefaultValue("PT5S") Duration readTimeout
) {

    private static final Duration MAX_REQUEST_TIMEOUT_BUDGET = Duration.ofSeconds(45);

    URI requiredBaseUri() {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalStateException("BRIEF base URL은 유효한 URI여야 합니다");
        }
        boolean supportedScheme = "https".equalsIgnoreCase(uri.getScheme())
                || ("http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost()));
        boolean rootPath = uri.getPath() == null
                || uri.getPath().isEmpty()
                || "/".equals(uri.getPath());
        if (!supportedScheme
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || !rootPath
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException(
                    "BRIEF base URL은 path, user info, query, fragment가 없는 HTTPS origin 또는 loopback HTTP origin이어야 합니다"
            );
        }
        validateExplicitPort(uri);
        return uri;
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
                    "BRIEF connect timeout과 read timeout의 합은 45초 이하여야 합니다"
            );
        }
    }

    private boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private void validateExplicitPort(URI uri) {
        String rawAuthority = uri.getRawAuthority();
        boolean portOmitted = rawAuthority.equalsIgnoreCase(uri.getHost())
                || rawAuthority.equalsIgnoreCase("[" + uri.getHost() + "]");
        int port = uri.getPort();
        if (!portOmitted && (port < 1 || port > 65_535)) {
            throw new IllegalStateException("BRIEF base URL의 명시 포트는 1~65535 범위여야 합니다");
        }
    }

    private Duration requiredPositiveTimeout(Duration timeout, String name) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("BRIEF " + name + "은 0보다 커야 합니다");
        }
        return timeout;
    }

    @Override
    public String toString() {
        return "BriefIntegrationProperties[deliveryEnabled=" + deliveryEnabled
                + ", baseUrl=<redacted>, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + "]";
    }
}
