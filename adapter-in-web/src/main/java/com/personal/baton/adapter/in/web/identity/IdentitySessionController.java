package com.personal.baton.adapter.in.web.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentitySessionController {

    private final SecurityContextLogoutHandler logoutHandler =
            new SecurityContextLogoutHandler();
    private final boolean oidcEnabled;

    public IdentitySessionController(
            @Value("${baton.identity.oidc.enabled:false}") boolean oidcEnabled
    ) {
        this.oidcEnabled = oidcEnabled;
    }

    @GetMapping("/auth/session")
    public ResponseEntity<IdentitySessionResponse> getSession(
            Authentication authentication,
            HttpServletRequest request
    ) {
        if (authentication == null
                    || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof BatonAccountPrincipal principal)) {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(IdentitySessionResponse.anonymous(oidcEnabled));
        }

        CsrfToken csrfToken = (CsrfToken) request.getAttribute(
                CsrfToken.class.getName()
        );
        if (csrfToken == null) {
            throw new IllegalStateException(
                    "Authenticated identity session is missing a CSRF token"
            );
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(IdentitySessionResponse.authenticated(
                        principal.accountId(),
                        csrfToken.getHeaderName(),
                        csrfToken.getToken(),
                        oidcEnabled
                ));
    }

    @PostMapping("/session/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
