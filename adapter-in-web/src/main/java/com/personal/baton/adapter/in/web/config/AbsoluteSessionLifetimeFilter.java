package com.personal.baton.adapter.in.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class AbsoluteSessionLifetimeFilter extends OncePerRequestFilter {

    static final String AUTHENTICATED_AT_SESSION_ATTRIBUTE =
            AbsoluteSessionLifetimeFilter.class.getName() + ".AUTHENTICATED_AT";

    private final Clock clock;
    private final Duration absoluteLifetime;

    AbsoluteSessionLifetimeFilter(Clock clock, Duration absoluteLifetime) {
        this.clock = Objects.requireNonNull(clock);
        this.absoluteLifetime = Objects.requireNonNull(absoluteLifetime);
        if (absoluteLifetime.isZero() || absoluteLifetime.isNegative()) {
            throw new IllegalArgumentException("absoluteLifetime must be positive");
        }
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && hasReachedAbsoluteLifetime(session)) {
            session.invalidate();
            SecurityContextHolder.clearContext();
        }
        filterChain.doFilter(request, response);
    }

    private boolean hasReachedAbsoluteLifetime(HttpSession session) {
        Object authenticatedAtAttribute = session.getAttribute(
                AUTHENTICATED_AT_SESSION_ATTRIBUTE
        );
        Instant lifetimeStartedAt = authenticatedAtAttribute instanceof Long authenticatedAt
                ? Instant.ofEpochMilli(authenticatedAt)
                : Instant.ofEpochMilli(session.getCreationTime());
        return !clock.instant().isBefore(lifetimeStartedAt.plus(absoluteLifetime));
    }
}
