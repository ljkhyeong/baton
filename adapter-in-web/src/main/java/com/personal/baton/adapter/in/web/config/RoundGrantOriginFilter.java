package com.personal.baton.adapter.in.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

final class RoundGrantOriginFilter extends OncePerRequestFilter {

    private static final String FETCH_SITE_HEADER = "Sec-Fetch-Site";
    private static final Pattern RESOURCE_GRANT_PATH = Pattern.compile(
            "^/api/v1/teams/[^/]+/seasons/[^/]+"
                    + "/role-resources/[^/]+/round-participation-grant$"
    );
    private static final Pattern ROOM_GRANT_PATH = Pattern.compile(
            "^/api/v1/round/rooms/[^/]+/participation-grant$"
    );
    private static final Pattern ROOM_GRANT_REFRESH_PATH = Pattern.compile(
            "^/round/rooms/[^/]+/participation-grant/refresh$"
    );

    private final String expectedOrigin;
    private final SecurityErrorResponseWriter responseWriter;

    RoundGrantOriginFilter(
            String expectedOrigin,
            SecurityErrorResponseWriter responseWriter
    ) {
        this.expectedOrigin = canonicalOrigin(expectedOrigin);
        this.responseWriter = responseWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!isGrantRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!singleHeader(request, HttpHeaders.ORIGIN).equals(List.of(expectedOrigin))
                || !singleHeader(request, FETCH_SITE_HEADER).equals(List.of("same-origin"))) {
            responseWriter.write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "ROUND_GRANT_ORIGIN_INVALID",
                    "ROUND 참여권은 동일 출처 요청에서만 발급할 수 있습니다"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    static boolean isGrantRequest(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && isGrantPath(request);
    }

    static boolean isGrantPath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String requestPath = request.getRequestURI();
        if (contextPath != null
                && !contextPath.isEmpty()
                && requestPath.startsWith(contextPath)) {
            requestPath = requestPath.substring(contextPath.length());
        }
        return RESOURCE_GRANT_PATH.matcher(requestPath).matches()
                || ROOM_GRANT_PATH.matcher(requestPath).matches()
                || ROOM_GRANT_REFRESH_PATH.matcher(requestPath).matches();
    }

    private List<String> singleHeader(HttpServletRequest request, String name) {
        var headers = request.getHeaders(name);
        if (headers == null) {
            return List.of();
        }
        return Collections.list(headers);
    }

    private static String canonicalOrigin(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null
                    ? ""
                    : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            if (!uri.isAbsolute()
                    || !(scheme.equals("http") || scheme.equals("https"))
                    || host.isBlank()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || (path != null && !path.isEmpty() && !path.equals("/"))) {
                throw new IllegalStateException(
                        "baton.round.grant.issuer는 http(s) origin이어야 합니다"
                );
            }
            return new URI(
                    scheme,
                    null,
                    host,
                    uri.getPort(),
                    null,
                    null,
                    null
            ).toString();
        } catch (IllegalArgumentException | URISyntaxException exception) {
            throw new IllegalStateException(
                    "baton.round.grant.issuer는 http(s) origin이어야 합니다",
                    exception
            );
        }
    }
}
