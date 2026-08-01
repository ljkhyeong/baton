package com.personal.baton.adapter.out.external.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

public final class TrustedHttpOrigin {

    private final URI uri;
    private final String scheme;
    private final String host;
    private final int port;
    private final int effectivePort;

    private TrustedHttpOrigin(URI uri, String scheme, String host, int port) {
        this.uri = uri;
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.effectivePort = effectivePort(scheme, port);
    }

    public static TrustedHttpOrigin from(URI candidate) {
        String scheme = normalizedScheme(candidate);
        String host = normalizedHost(candidate);
        String path = candidate == null ? null : candidate.getRawPath();
        if (candidate == null
                || !candidate.isAbsolute()
                || !(scheme.equals("http") || scheme.equals("https"))
                || host.isBlank()
                || candidate.getUserInfo() != null
                || candidate.getQuery() != null
                || candidate.getFragment() != null
                || (path != null && !path.isEmpty() && !path.equals("/"))) {
            throw new IllegalArgumentException("http(s) origin이 필요합니다");
        }
        try {
            URI normalized = new URI(
                    scheme,
                    null,
                    host,
                    candidate.getPort(),
                    null,
                    null,
                    null
            );
            return new TrustedHttpOrigin(normalized, scheme, host, candidate.getPort());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("http(s) origin을 정규화할 수 없습니다", exception);
        }
    }

    public URI uri() {
        return uri;
    }

    public String scheme() {
        return scheme;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public boolean hasSameOriginAs(URI candidate) {
        if (candidate == null) {
            return false;
        }
        String candidateScheme = normalizedScheme(candidate);
        String candidateHost = normalizedHost(candidate);
        int candidatePort = effectivePort(candidateScheme, candidate.getPort());
        return scheme.equals(candidateScheme)
                && host.equals(candidateHost)
                && effectivePort == candidatePort;
    }

    public URI withPath(String path) {
        if (path == null
                || !path.startsWith("/")
                || path.startsWith("//")
                || path.indexOf('?') >= 0
                || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("origin 경로는 안전한 절대 경로여야 합니다");
        }
        try {
            return new URI(scheme, null, host, port, path, null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("origin 경로를 조립할 수 없습니다", exception);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TrustedHttpOrigin origin)) {
            return false;
        }
        return effectivePort == origin.effectivePort
                && scheme.equals(origin.scheme)
                && host.equals(origin.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scheme, host, effectivePort);
    }

    @Override
    public String toString() {
        return uri.toString();
    }

    private static String normalizedScheme(URI uri) {
        return uri == null || uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static String normalizedHost(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return "";
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return switch (scheme) {
            case "http" -> 80;
            case "https" -> 443;
            default -> -1;
        };
    }
}
