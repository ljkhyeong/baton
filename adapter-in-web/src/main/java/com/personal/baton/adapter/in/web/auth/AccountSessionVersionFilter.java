package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.ErrorResponse;
import com.personal.baton.adapter.in.web.HttpObservationErrors;
import com.personal.baton.adapter.in.web.security.SecurityErrorResponseWriter;
import com.personal.baton.application.identity.port.in.ValidateAccountSessionUseCase;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

public final class AccountSessionVersionFilter extends OncePerRequestFilter {
    private final ValidateAccountSessionUseCase useCase;
    private final SecurityErrorResponseWriter errorResponseWriter;
    private final LogoutHandler logoutHandler = new CompositeLogoutHandler(
            new SecurityContextLogoutHandler(), new CookieClearingLogoutHandler("JSESSIONID"));

    public AccountSessionVersionFilter(
            ValidateAccountSessionUseCase useCase, SecurityErrorResponseWriter errorResponseWriter
    ) {
        this.useCase = useCase;
        this.errorResponseWriter = errorResponseWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain
    ) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccountSessionPrincipal principal) {
            final boolean current;
            try {
                current = useCase.isAccountSessionCurrent(principal.accountId(), principal.sessionVersion());
            } catch (RuntimeException exception) {
                var failure = IdentityInfrastructureFailures.find(exception);
                if (failure.isEmpty()) {
                    throw exception;
                }
                HttpObservationErrors.mark(request, failure.get());
                errorResponseWriter.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        new ErrorResponse("IDENTITY_TEMPORARILY_UNAVAILABLE",
                                "로그인 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요"));
                return;
            }
            if (!current) {
                logoutHandler.logout(request, response, authentication);
            }
        }
        chain.doFilter(request, response);
    }
}
