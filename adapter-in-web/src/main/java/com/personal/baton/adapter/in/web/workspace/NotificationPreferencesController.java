package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.workspace.port.in.NotificationPreferencesUseCase;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationPreferencesController {
    public static final String PATH = "/api/v1/notification-preferences";
    private final NotificationPreferencesUseCase useCase;
    public NotificationPreferencesController(NotificationPreferencesUseCase useCase) { this.useCase = useCase; }
    @GetMapping(PATH)
    public ResponseEntity<NotificationPreferencesResponse> get(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal) {
        return ok(useCase.get(principal.accountId()));
    }
    @PostMapping(PATH)
    public ResponseEntity<NotificationPreferencesResponse> configure(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody NotificationPreferencesRequest request) {
        if (!principal.accountId().equals(request.expectedAccountId())) throw new AccountMembershipConflictException("로그인 계정이 변경되었습니다. 새로고침해 주세요.");
        return ok(useCase.configure(principal.accountId(), new NotificationPreferencesUseCase.ConfigurePreferencesCommand(request.expectedVersion(),
                request.deadlineSoonEnabled(), request.overdueEnabled(), request.handoffEnabled(), request.deadlineLeadHours())));
    }
    private ResponseEntity<NotificationPreferencesResponse> ok(NotificationPreferencesUseCase.PreferencesResult result) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(NotificationPreferencesResponse.from(result));
    }
}
