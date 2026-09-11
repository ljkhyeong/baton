package com.personal.baton.adapter.out.external.http;

import java.net.URI;

public final class ExternalHttpOrigin {

    private ExternalHttpOrigin() {
    }

    public static URI requireHttps(String subject, String value) {
        return require(subject, value, false);
    }

    public static URI requireHttpsOrLoopbackHttp(String subject, String value) {
        return require(subject, value, true);
    }

    private static URI require(String subject, String value, boolean allowLoopbackHttp) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ignored) {
            throw new IllegalStateException(subject + "은 유효한 URI여야 합니다");
        }
        boolean rootPath = uri.getPath() == null
                || uri.getPath().isEmpty()
                || "/".equals(uri.getPath());
        boolean supportedScheme = "https".equalsIgnoreCase(uri.getScheme())
                || (allowLoopbackHttp
                && "http".equalsIgnoreCase(uri.getScheme())
                && isLoopback(uri.getHost()));
        if (!supportedScheme
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || !rootPath
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalStateException(
                    subject + "은 경로, 사용자 정보, 쿼리, 조각이 없는 절대 HTTPS 출처여야 합니다"
            );
        }
        String rawAuthority = uri.getRawAuthority();
        boolean portOmitted = rawAuthority.equalsIgnoreCase(uri.getHost());
        int port = uri.getPort();
        if (!portOmitted && (port < 1 || port > 65_535)) {
            throw new IllegalStateException(
                    subject + "의 명시 포트는 1~65535 범위여야 합니다"
            );
        }
        return uri;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }
}
