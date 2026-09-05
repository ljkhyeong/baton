package com.personal.baton.adapter.in.web.auth;

import com.personal.baton.application.identity.port.in.DeactivateAccountUseCase;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.authentication.logout.CompositeLogoutHandler;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.LogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountDeactivationController {
    public static final String PATH = "/api/v1/auth/account-deactivations";
    public record AccountDeactivationRequest(@NotNull UUID expectedAccountId) {}
    private final DeactivateAccountUseCase useCase;
    private final LogoutHandler logout = new CompositeLogoutHandler(new CookieClearingLogoutHandler("JSESSIONID"), new SecurityContextLogoutHandler());

    public AccountDeactivationController(DeactivateAccountUseCase useCase) { this.useCase = useCase; }

    @PostMapping(PATH)
    public ResponseEntity<Void> deactivateAccount(@AuthenticationPrincipal AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody AccountDeactivationRequest input, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        if (!principal.accountId().equals(input.expectedAccountId())) throw new AccountMembershipConflictException("화면에 표시된 계정과 현재 로그인 계정이 다릅니다");
        useCase.deactivateAccount(principal.accountId());
        logout.logout(request, response, authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
