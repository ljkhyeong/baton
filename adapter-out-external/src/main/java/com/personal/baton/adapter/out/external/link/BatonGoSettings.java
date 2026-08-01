package com.personal.baton.adapter.out.external.link;

import com.personal.baton.adapter.out.external.http.TrustedHttpOrigin;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;

public final class BatonGoSettings {

    private final boolean enabled;
    private final URI createLinkEndpoint;
    private final TrustedHttpOrigin publicOrigin;
    private final String managementToken;
    private final TrustedHttpOrigin roundPublicOrigin;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    private BatonGoSettings(
            boolean enabled,
            URI createLinkEndpoint,
            TrustedHttpOrigin publicOrigin,
            String managementToken,
            TrustedHttpOrigin roundPublicOrigin,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        this.enabled = enabled;
        this.createLinkEndpoint = createLinkEndpoint;
        this.publicOrigin = publicOrigin;
        this.managementToken = managementToken;
        this.roundPublicOrigin = roundPublicOrigin;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public static BatonGoSettings from(BatonGoProperties properties) {
        Objects.requireNonNull(properties, "BATON GO 설정은 필수입니다");
        if (!properties.isEnabled()) {
            return new BatonGoSettings(false, null, null, null, null, null, null);
        }
        TrustedHttpOrigin baseOrigin = requireHttpOrigin(properties.getBaseUrl(), "base-url");
        TrustedHttpOrigin publicOrigin = requireHttpOrigin(
                properties.getPublicBaseUrl(),
                "public-base-url"
        );
        TrustedHttpOrigin roundOrigin = requireHttpOrigin(
                properties.getRoundPublicBaseUrl(),
                "round-public-base-url"
        );
        String managementToken = properties.getManagementToken();
        Duration connectTimeout = properties.getConnectTimeout();
        Duration readTimeout = properties.getReadTimeout();
        if (managementToken == null || managementToken.length() < 32) {
            throw new IllegalStateException("management-token은 32자 이상이어야 합니다");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalStateException("connect-timeout은 양수여야 합니다");
        }
        if (readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalStateException("read-timeout은 양수여야 합니다");
        }
        return new BatonGoSettings(
                true,
                createLinkEndpoint(baseOrigin),
                publicOrigin,
                managementToken,
                roundOrigin,
                connectTimeout,
                readTimeout
        );
    }

    public boolean enabled() {
        return enabled;
    }

    public URI createLinkEndpoint() {
        return createLinkEndpoint;
    }

    public TrustedHttpOrigin publicOrigin() {
        return publicOrigin;
    }

    public String managementToken() {
        return managementToken;
    }

    public TrustedHttpOrigin roundPublicOrigin() {
        return roundPublicOrigin;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    private static TrustedHttpOrigin requireHttpOrigin(URI uri, String name) {
        try {
            return TrustedHttpOrigin.from(uri);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(name + " 설정은 http(s) origin이어야 합니다");
        }
    }

    private static URI createLinkEndpoint(TrustedHttpOrigin baseOrigin) {
        return baseOrigin.withPath("/api/v1/links");
    }
}
