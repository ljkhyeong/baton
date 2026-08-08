package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.security.AccountSessionRequestMatchers;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class LocalLoginRateLimitFilter extends OncePerRequestFilter {

    private static final ErrorResponse RATE_LIMITED = new ErrorResponse(
            "AUTH_RATE_LIMITED",
            "인증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"
    );
    private static final RequestMatcher LOCAL_LOGIN =
            AccountSessionRequestMatchers.localLogin();

    private final AuthRateLimiter rateLimiter;
    private final SecurityErrorResponseWriter errorResponseWriter;

    public LocalLoginRateLimitFilter(
            AuthRateLimiter rateLimiter,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !LOCAL_LOGIN.matches(request);
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
            response.setHeader(
                    HttpHeaders.RETRY_AFTER,
                    Long.toString(exception.retryAfterSeconds())
            );
            errorResponseWriter.write(response, 429, RATE_LIMITED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
