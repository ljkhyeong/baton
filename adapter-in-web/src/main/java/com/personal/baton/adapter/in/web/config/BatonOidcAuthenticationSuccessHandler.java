package com.personal.baton.adapter.in.web.config;

import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.adapter.in.web.identity.ResolvedBatonOidcUser;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.context.SecurityContextRepository;

final class BatonOidcAuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final List<SimpleGrantedAuthority> ACCOUNT_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final SecurityContextRepository sessionSecurityContextRepository;
    private final Clock clock;
    private final AuthenticationSuccessHandler redirectHandler =
            new SimpleUrlAuthenticationSuccessHandler("/");

    BatonOidcAuthenticationSuccessHandler(
            SecurityContextRepository sessionSecurityContextRepository,
            Clock clock
    ) {
        this.sessionSecurityContextRepository = sessionSecurityContextRepository;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) throws IOException, ServletException {
        if (!(authentication.getPrincipal() instanceof ResolvedBatonOidcUser oidcUser)) {
            clearAuthentication(request);
            throw new ServletException("OIDC login did not resolve a BATON account principal");
        }

        Authentication accountAuthentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        new BatonAccountPrincipal(oidcUser.accountId()),
                        null,
                        ACCOUNT_AUTHORITIES
                );
        SecurityContext accountContext = SecurityContextHolder.createEmptyContext();
        accountContext.setAuthentication(accountAuthentication);
        SecurityContextHolder.setContext(accountContext);
        request.getSession(true).setAttribute(
                AbsoluteSessionLifetimeFilter.AUTHENTICATED_AT_SESSION_ATTRIBUTE,
                clock.instant().toEpochMilli()
        );
        sessionSecurityContextRepository.saveContext(
                accountContext,
                request,
                response
        );
        redirectHandler.onAuthenticationSuccess(request, response, accountAuthentication);
    }

    private void clearAuthentication(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
