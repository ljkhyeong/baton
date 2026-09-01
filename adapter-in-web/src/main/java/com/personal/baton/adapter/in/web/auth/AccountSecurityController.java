package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.adapter.in.web.auth.AuthRequests.LocalPasswordChangeRequest;
import com.personal.baton.adapter.in.web.auth.AuthResponses.AccountResponse;
import com.personal.baton.application.identity.port.in.AccountSecurityUseCase;
import com.personal.baton.application.identity.port.in.AccountSecurityUseCase.ChangeLocalPasswordCommand;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountSecurityController {

    public static final String ACCOUNT_PATH = "/api/v1/auth/account";
    public static final String LOCAL_PASSWORD_CHANGES_PATH =
            "/api/v1/auth/local/password-changes";
    public static final String SESSION_REVOCATIONS_PATH =
            "/api/v1/auth/session-revocations";

    private final AccountSecurityUseCase accountSecurityUseCase;
    private final LogoutHandler logoutHandler = new CompositeLogoutHandler(
            new CookieClearingLogoutHandler("JSESSIONID"),
            new SecurityContextLogoutHandler()
    );

    public AccountSecurityController(AccountSecurityUseCase accountSecurityUseCase) {
        this.accountSecurityUseCase = accountSecurityUseCase;
    }

    @GetMapping(ACCOUNT_PATH)
    public ResponseEntity<AccountResponse> getAccount(
            @AuthenticationPrincipal AuthenticatedAccountPrincipal principal
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AccountResponse.from(accountSecurityUseCase.getAccount(
                        principal.accountId()
                )));
    }

    @PostMapping(LOCAL_PASSWORD_CHANGES_PATH)
    public ResponseEntity<Void> changeLocalPassword(
            @AuthenticationPrincipal AuthenticatedAccountPrincipal principal,
            Authentication authentication,
            @Valid @RequestBody LocalPasswordChangeRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        accountSecurityUseCase.changeLocalPassword(new ChangeLocalPasswordCommand(
                principal.accountId(),
                request.currentPassword(),
                request.newPassword()
        ));
        return logoutCurrentSession(
                servletRequest,
                servletResponse,
                authentication
        );
    }

    @PostMapping(SESSION_REVOCATIONS_PATH)
    public ResponseEntity<Void> revokeAllSessions(
            @AuthenticationPrincipal AuthenticatedAccountPrincipal principal,
            Authentication authentication,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        accountSecurityUseCase.revokeAllSessions(principal.accountId());
        return logoutCurrentSession(
                servletRequest,
                servletResponse,
                authentication
        );
    }

    private ResponseEntity<Void> logoutCurrentSession(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication
    ) {
        logoutHandler.logout(request, response, authentication);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
