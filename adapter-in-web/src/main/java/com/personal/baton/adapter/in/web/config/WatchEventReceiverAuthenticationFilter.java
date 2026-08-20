package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import com.personal.baton.adapter.in.web.watch.WatchHealthEventController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

public final class WatchEventReceiverAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final ErrorResponse UNAUTHORIZED_RESPONSE = new ErrorResponse(
            "UNAUTHORIZED",
            "인증 정보가 올바르지 않습니다"
    );

    private final WatchEventReceiverAuthentication authentication;
    private final SecurityErrorResponseWriter errorResponseWriter;

    public WatchEventReceiverAuthenticationFilter(
            WatchEventReceiverAuthentication authentication,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        this.authentication = Objects.requireNonNull(authentication);
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter);
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
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        errorResponseWriter.write(
                response,
                HttpServletResponse.SC_UNAUTHORIZED,
                UNAUTHORIZED_RESPONSE
        );
    }
}
