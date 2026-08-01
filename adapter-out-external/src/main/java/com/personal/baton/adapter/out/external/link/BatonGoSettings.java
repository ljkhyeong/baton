package com.personal.baton.adapter.out.external.link;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public final class BatonGoSettings {

    private final boolean enabled;
    private final URI createLinkEndpoint;
    private final URI publicOrigin;
    private final String managementToken;
    private final URI roundPublicOrigin;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    private BatonGoSettings(
            boolean enabled,
            URI createLinkEndpoint,
            URI publicOrigin,
            String managementToken,
            URI roundPublicOrigin,
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
        URI baseOrigin = requireHttpOrigin(properties.getBaseUrl(), "base-url");
        URI publicOrigin = requireHttpOrigin(
                properties.getPublicBaseUrl(),
                "public-base-url"
        );
        URI roundOrigin = requireHttpOrigin(
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

    public URI publicOrigin() {
        return publicOrigin;
    }

    public String managementToken() {
        return managementToken;
    }

    public URI roundPublicOrigin() {
        return roundPublicOrigin;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

    private static URI requireHttpOrigin(URI uri, String name) {
        String scheme = uri == null ? "" : normalizedScheme(uri);
        String path = uri == null ? null : uri.getRawPath();
        if (uri == null
                || !uri.isAbsolute()
                || !(scheme.equals("http") || scheme.equals("https"))
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (path != null && !path.isEmpty() && !path.equals("/"))) {
            throw new IllegalStateException(name + " 설정은 http(s) origin이어야 합니다");
        }
        try {
            return new URI(
                    scheme,
                    null,
                    normalizedHost(uri),
                    uri.getPort(),
                    null,
                    null,
                    null
            );
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(name + " 설정을 정규화할 수 없습니다", exception);
        }
    }

    private static URI createLinkEndpoint(URI baseOrigin) {
        try {
            return new URI(
                    normalizedScheme(baseOrigin),
                    null,
                    normalizedHost(baseOrigin),
                    baseOrigin.getPort(),
                    "/api/v1/links",
                    null,
                    null
            );
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("base-url 설정을 정규화할 수 없습니다", exception);
        }
    }

    private static String normalizedScheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static String normalizedHost(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }
}
