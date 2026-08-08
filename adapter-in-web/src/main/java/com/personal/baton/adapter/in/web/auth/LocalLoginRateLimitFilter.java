package com.personal.baton.adapter.in.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public final class LocalLoginRateLimitFilter extends OncePerRequestFilter {

    private static final String RATE_LIMITED_RESPONSE =
            "{\"code\":\"AUTH_RATE_LIMITED\",\"message\":\"인증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요\"}";

    private final AuthRateLimiter rateLimiter;

    public LocalLoginRateLimitFilter(AuthRateLimiter rateLimiter) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !AuthController.LOCAL_SESSION_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            rateLimiter.checkLogin(request.getRemoteAddr(), request.getParameter("email"));
        } catch (AuthRateLimitExceededException exception) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setHeader(
                    HttpHeaders.RETRY_AFTER,
                    Long.toString(exception.retryAfterSeconds())
            );
            response.getWriter().write(RATE_LIMITED_RESPONSE);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
