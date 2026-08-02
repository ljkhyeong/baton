package com.personal.baton.bootstrap.config;

import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton.watch.event-receiver")
public record WatchEventReceiverProperties(
        boolean enabled,
        String bearerToken
) {

    private static final Pattern BEARER_TOKEN_PATTERN = Pattern.compile(
            "[A-Za-z0-9._~-]{32,200}"
    );

    public WatchEventReceiverProperties {
        bearerToken = Objects.requireNonNullElse(bearerToken, "");
    }

    String requiredBearerToken() {
        if (!BEARER_TOKEN_PATTERN.matcher(bearerToken).matches()) {
            throw new IllegalStateException(
                    "WATCH 이벤트 수신 bearer token은 32~200자의 URL-safe ASCII여야 합니다"
            );
        }
        return bearerToken;
    }

    @Override
    public String toString() {
        return "WatchEventReceiverProperties[enabled=" + enabled + ", bearerToken=<redacted>]";
    }
}
