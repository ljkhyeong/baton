package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.identity.BatonAccountPrincipal;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization;
import com.personal.baton.application.workspace.port.in.WorkspaceAuthorization.SessionAccount;
import java.util.function.Function;
import org.springframework.security.core.Authentication;

final class WorkspaceControllerSupport {

    static final String ACCESS_KEY_HEADER = "X-Baton-Access-Key";
    static final String CREATION_KEY_HEADER = "X-Baton-Creation-Key";
    static final String RECOVERY_KEY_HEADER = "X-Baton-Recovery-Key";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private WorkspaceControllerSupport() {
    }

    static <T> T invokeAuthorized(
            String accessKey,
            Authentication principal,
            Function<String, T> legacyCall,
            Function<WorkspaceAuthorization, T> sessionCall
    ) {
        if (accessKey != null && !accessKey.isBlank()) {
            return legacyCall.apply(accessKey);
        }
        if (principal != null
                && principal.getPrincipal() instanceof BatonAccountPrincipal accountPrincipal) {
            return sessionCall.apply(new SessionAccount(
                    new AuthenticatedAccount(accountPrincipal.accountId())
            ));
        }
        throw new WorkspaceAccessDeniedException();
    }

    static AuthenticatedAccount authenticatedAccount(Authentication principal) {
        if (principal != null
                && principal.getPrincipal() instanceof BatonAccountPrincipal accountPrincipal) {
            return new AuthenticatedAccount(accountPrincipal.accountId());
        }
        throw new WorkspaceAccessDeniedException();
    }
}
