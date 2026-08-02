package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.watch.WatchHealthEventController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public final class WatchEventReceiverAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_RESPONSE = """
            {"code":"UNAUTHORIZED","message":"인증 정보가 올바르지 않습니다"}
            """;

    private final WatchEventReceiverAuthentication authentication;

    public WatchEventReceiverAuthenticationFilter(WatchEventReceiverAuthentication authentication) {
        this.authentication = Objects.requireNonNull(authentication);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return !requestPath.equals(WatchHealthEventController.PATH)
                && !requestPath.startsWith(WatchHealthEventController.PATH + "/")
                && !requestPath.startsWith(WatchHealthEventController.PATH + ";");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!authentication.authenticates(extractSingleBearerToken(request))) {
            writeUnauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String extractSingleBearerToken(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String authorization = values.nextElement();
        if (values.hasMoreElements()
                || authorization == null
                || authorization.length() <= BEARER_PREFIX.length()
                || !authorization.regionMatches(
                        true,
                        0,
                        BEARER_PREFIX,
                        0,
                        BEARER_PREFIX.length()
                )) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length());
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED_RESPONSE);
    }
}
