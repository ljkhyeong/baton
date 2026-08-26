package com.personal.baton.bootstrap.config;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

final class OutboundHttpSettings {

    private static final Duration MAX_REQUEST_TIMEOUT_BUDGET = Duration.ofSeconds(45);
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z0-9._~-]{32,200}"
    );

    private OutboundHttpSettings() {
    }

    static URI requireHttpsOrigin(String service, String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalStateException(service + " base URL은 유효한 URI여야 합니다");
        }
        boolean rootPath = uri.getPath() == null
                || uri.getPath().isEmpty()
                || "/".equals(uri.getPath());
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || !rootPath
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException(
                    service
                            + " base URL은 path, user info, query, fragment가 없는 절대 HTTPS origin이어야 합니다"
            );
        }
        String rawAuthority = uri.getRawAuthority();
        boolean portOmitted = rawAuthority.equalsIgnoreCase(uri.getHost());
        int port = uri.getPort();
        if (!portOmitted && (port < 1 || port > 65_535)) {
            throw new IllegalStateException(
                    service + " base URL의 명시 포트는 1~65535 범위여야 합니다"
            );
        }
        return uri;
    }

    static String requireBearerToken(String service, String value) {
        if (!BEARER_TOKEN_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException(
                    service + " bearer token은 32~200자의 URL-safe ASCII여야 합니다"
            );
        }
        return value;
    }

    static Duration requirePositiveTimeout(
            String service,
            String name,
            Duration timeout
    ) {
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException(service + " " + name + "은 0보다 커야 합니다");
        }
        return timeout;
    }

    static void validateRequestTimeoutBudget(
            String service,
            Duration connect,
            Duration read
    ) {
        if (connect.compareTo(MAX_REQUEST_TIMEOUT_BUDGET) >= 0
                || read.compareTo(MAX_REQUEST_TIMEOUT_BUDGET.minus(connect)) > 0) {
            throw new IllegalStateException(
                    service + " connect timeout과 read timeout의 합은 45초 이하여야 합니다"
            );
        }
    }
}
