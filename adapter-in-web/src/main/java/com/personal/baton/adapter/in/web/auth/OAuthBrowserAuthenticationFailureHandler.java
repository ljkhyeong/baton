package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.HttpObservationErrors;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

public final class OAuthBrowserAuthenticationFailureHandler
        implements AuthenticationFailureHandler {

    static final String LOGIN_FAILED_REDIRECT =
            "/login?oauthError=login_failed";
    static final String TEMPORARILY_UNAVAILABLE_REDIRECT =
            "/login?oauthError=temporarily_unavailable";
    static final String REFERRER_POLICY_HEADER = "Referrer-Policy";

    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception
    ) throws IOException, ServletException {
        Optional<RuntimeException> infrastructureFailure =
                IdentityInfrastructureFailures.find(exception);
        String redirect = LOGIN_FAILED_REDIRECT;
        if (infrastructureFailure.isPresent()) {
            HttpObservationErrors.mark(request, infrastructureFailure.get());
            redirect = TEMPORARILY_UNAVAILABLE_REDIRECT;
        }
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(REFERRER_POLICY_HEADER, "no-referrer");
        redirectStrategy.sendRedirect(request, response, redirect);
    }
}
