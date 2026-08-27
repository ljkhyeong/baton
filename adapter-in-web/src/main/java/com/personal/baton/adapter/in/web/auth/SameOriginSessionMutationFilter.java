package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.security.AccountSessionRequestMatchers;
import com.personal.baton.adapter.in.web.security.SameOriginRequestPolicy;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class SameOriginSessionMutationFilter extends OncePerRequestFilter {

    private static final ErrorResponse ORIGIN_DENIED = new ErrorResponse(
            "ORIGIN_DENIED",
            "동일 출처 요청만 허용됩니다"
    );
    private static final RequestMatcher PROTECTED_MUTATION =
            AccountSessionRequestMatchers.sameOriginSessionMutation();

    private final SecurityErrorResponseWriter errorResponseWriter;

    public SameOriginSessionMutationFilter(SecurityErrorResponseWriter errorResponseWriter) {
        this.errorResponseWriter = Objects.requireNonNull(errorResponseWriter);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PROTECTED_MUTATION.matches(request);
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
                    ORIGIN_DENIED
            );
            return;
        }
        filterChain.doFilter(request, response);
    }
}
