package com.personal.baton.bootstrap.config;

import java.time.Duration;
import java.util.regex.Pattern;

final class OutboundHttpSettings {

    private static final Duration MAX_REQUEST_TIMEOUT_BUDGET = Duration.ofSeconds(45);
    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z0-9._~-]{32,200}"
    );

    private OutboundHttpSettings() {
    }

    static String requireBearerToken(String service, String value) {
        if (!BEARER_TOKEN_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException(
                    service + " Bearer 토큰은 32~200자의 URL 안전 ASCII여야 합니다"
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
                    service + " 연결 시간 제한과 읽기 시간 제한의 합은 45초 이하여야 합니다"
            );
        }
    }
}
