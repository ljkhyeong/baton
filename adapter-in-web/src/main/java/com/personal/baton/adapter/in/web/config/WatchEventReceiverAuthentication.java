package com.personal.baton.adapter.in.web.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

public final class WatchEventReceiverAuthentication {

    private final boolean enabled;
    private final byte[] bearerToken;

    private WatchEventReceiverAuthentication(boolean enabled, byte[] bearerToken) {
        this.enabled = enabled;
        this.bearerToken = bearerToken.clone();
    }

    public static WatchEventReceiverAuthentication disabled() {
        return new WatchEventReceiverAuthentication(false, new byte[0]);
    }

    public static WatchEventReceiverAuthentication enabled(String bearerToken) {
        Objects.requireNonNull(bearerToken, "WATCH 이벤트 수신 Bearer 토큰은 필수입니다");
        return new WatchEventReceiverAuthentication(
                true,
                bearerToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    boolean authenticates(String presentedToken) {
        if (!enabled || presentedToken == null) {
            return false;
        }
        return MessageDigest.isEqual(
                bearerToken,
                presentedToken.getBytes(StandardCharsets.UTF_8)
        );
    }

    @Override
    public String toString() {
        return "WatchEventReceiverAuthentication[enabled=" + enabled + ", bearerToken=<redacted>]";
    }
}
