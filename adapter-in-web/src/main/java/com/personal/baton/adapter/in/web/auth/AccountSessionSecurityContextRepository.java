package com.personal.baton.adapter.in.web.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.SecurityContextRepository;

public final class AccountSessionSecurityContextRepository
        implements SecurityContextRepository {

    private static final List<GrantedAuthority> ACCOUNT_AUTHORITIES =
            List.of(new SimpleGrantedAuthority("ROLE_ACCOUNT"));

    private final SecurityContextRepository delegate;

    public AccountSessionSecurityContextRepository(SecurityContextRepository delegate) {
        this.delegate = Objects.requireNonNull(delegate);
    }

    @SuppressWarnings("deprecation")
    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return delegate.loadContext(requestResponseHolder);
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return delegate.loadDeferredContext(request);
    }

    @Override
    public void saveContext(
            SecurityContext context,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Authentication authentication = context.getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            delegate.saveContext(context, request, response);
            return;
        }
        if (authentication.getPrincipal()
                instanceof AuthenticatedAccountPrincipal principal) {
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new AccountSessionPrincipal(principal.accountId(), principal.sessionVersion()),
                    null,
                    ACCOUNT_AUTHORITIES
            ));
            delegate.saveContext(context, request, response);
            return;
        }
        if (authentication instanceof OAuth2AuthenticationToken) {
            context.setAuthentication(null);
            delegate.saveContext(context, request, response);
            throw new IllegalStateException("인증된 BATON 계정 principal이 없습니다");
        }
        delegate.saveContext(context, request, response);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return delegate.containsContext(request);
    }
}
