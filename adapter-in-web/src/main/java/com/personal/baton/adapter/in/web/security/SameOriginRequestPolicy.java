package com.personal.baton.adapter.in.web.security;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.Locale;
import org.springframework.http.HttpHeaders;

public final class SameOriginRequestPolicy {

    private static final String SEC_FETCH_SITE = "Sec-Fetch-Site";
    private static final String SAME_ORIGIN = "same-origin";

    private SameOriginRequestPolicy() {
    }

    public static boolean allows(HttpServletRequest request) {
        return hasExactSameOrigin(request)
                && SAME_ORIGIN.equals(request.getHeader(SEC_FETCH_SITE));
    }

    private static boolean hasExactSameOrigin(HttpServletRequest request) {
        Enumeration<String> origins = request.getHeaders(HttpHeaders.ORIGIN);
        if (origins == null || !origins.hasMoreElements()) {
            return false;
        }
        String rawOrigin = origins.nextElement();
        if (origins.hasMoreElements()) {
            return false;
        }
        try {
            URI origin = new URI(rawOrigin);
            return isHttp(origin.getScheme())
                    && origin.getUserInfo() == null
                    && origin.getHost() != null
                    && (origin.getRawPath() == null || origin.getRawPath().isEmpty())
                    && origin.getRawQuery() == null
                    && origin.getRawFragment() == null
                    && origin.getScheme().equalsIgnoreCase(request.getScheme())
                    && origin.getHost().equalsIgnoreCase(request.getServerName())
                    && effectivePort(origin.getScheme(), origin.getPort())
                    == effectivePort(request.getScheme(), request.getServerPort());
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static boolean isHttp(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    private static int effectivePort(String scheme, int port) {
        if (port >= 0) {
            return port;
        }
        return "https".equals(scheme.toLowerCase(Locale.ROOT)) ? 443 : 80;
    }
}
