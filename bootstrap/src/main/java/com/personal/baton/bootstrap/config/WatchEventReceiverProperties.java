package com.personal.baton.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton.watch.event-receiver")
public record WatchEventReceiverProperties(
        boolean enabled,
        @DefaultValue("") String bearerToken
) {

    String requiredBearerToken() {
        return OutboundHttpSettings.requireBearerToken("WATCH 이벤트 수신", bearerToken);
    }

    @Override
    public String toString() {
        return "WatchEventReceiverProperties[enabled=" + enabled + ", bearerToken=<redacted>]";
    }
}
