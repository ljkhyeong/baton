package com.personal.baton.adapter.in.web.roundauth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.auth.AuthRateLimitExceededException;
import com.personal.baton.adapter.in.web.auth.AuthRateLimiter;
import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.adapter.in.web.security.AccountSessionRequestMatchers;
import com.personal.baton.adapter.in.web.security.SameOriginRequestPolicy;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class RoundGrantAdmissionFilter extends OncePerRequestFilter {

    private static final ErrorResponse REQUEST_FORBIDDEN = new ErrorResponse(
            "REQUEST_FORBIDDEN",
            "동일 출처 요청만 허용됩니다"
    );
    private static final ErrorResponse AUTHENTICATION_REQUIRED = new ErrorResponse(
            "AUTHENTICATION_REQUIRED",
            "BATON 계정 로그인이 필요합니다"
    );
    private static final ErrorResponse RATE_LIMITED = new ErrorResponse(
            "AUTH_RATE_LIMITED",
            "인증 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"
    );
    private static final RequestMatcher ROUND_GRANT_REFRESH =
            AccountSessionRequestMatchers.roundGrantRefresh();

    private final AuthRateLimiter rateLimiter;
    private final SecurityErrorResponseWriter errorResponseWriter;

    public RoundGrantAdmissionFilter(
            AuthRateLimiter rateLimiter,
            SecurityErrorResponseWriter errorResponseWriter
    ) {
        this.rateLimiter = Objects.requireNonNull(rateLimiter);
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ROUND_GRANT_REFRESH.matches(request);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!SameOriginRequestPolicy.allows(request)) {
            errorResponseWriter.write(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    REQUEST_FORBIDDEN
            );
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal()
                instanceof AuthenticatedAccountPrincipal principal)) {
            String roomId = roomId(request);
            if (roomId != null) {
                response.addHeader(
                        HttpHeaders.SET_COOKIE,
                        RoundGrantCookie.expire(roomId).toString()
                );
            }
            errorResponseWriter.write(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    AUTHENTICATION_REQUIRED
            );
            return;
        }
        String roomId = roomId(request);
        if (roomId != null) {
            try {
                rateLimiter.checkRoundGrant(
                        request.getRemoteAddr(),
                        principal.accountId(),
                        roomId
                );
            } catch (AuthRateLimitExceededException exception) {
                response.setHeader(
                        HttpHeaders.RETRY_AFTER,
                        Long.toString(exception.retryAfterSeconds())
                );
                errorResponseWriter.write(
                        response,
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        RATE_LIMITED
                );
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private String roomId(HttpServletRequest request) {
        String candidate = ROUND_GRANT_REFRESH.matcher(request)
                .getVariables()
                .get("roomId");
        if (candidate == null) {
            return null;
        }
        try {
            RoundGrantCookie.path(candidate);
            return candidate;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
