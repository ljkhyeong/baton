package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.workspace.port.in.WorkspaceNotificationUseCase;
import com.personal.baton.application.workspace.port.in.WorkspaceNotificationUseCase.NotificationInboxResult;
import com.personal.baton.domain.workspace.WorkspaceNotificationKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkspaceNotificationController {
    public static final String PATH = "/api/v1/teams/{teamId}/seasons/{seasonId}/notifications";
    public static final String READ_PATH = PATH + "/{notificationId}/read";
    private final WorkspaceNotificationUseCase useCase;
    public WorkspaceNotificationController(WorkspaceNotificationUseCase useCase) { this.useCase = useCase; }

    @GetMapping(PATH)
    public ResponseEntity<NotificationInboxResponse> inbox(@PathVariable UUID teamId, @PathVariable UUID seasonId,
            @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(NotificationInboxResponse.from(
                useCase.getInbox(teamId, seasonId, accessKey, principal.accountId())));
    }

    @PostMapping(READ_PATH)
    public ResponseEntity<NotificationInboxResponse> read(@PathVariable UUID teamId, @PathVariable UUID seasonId,
            @PathVariable UUID notificationId,
            @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody ReadNotificationRequest request) {
        if (!principal.accountId().equals(request.expectedAccountId())) {
            throw new AccountMembershipConflictException("로그인 계정이 변경되었습니다. 새로고침한 뒤 다시 확인해 주세요.");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(NotificationInboxResponse.from(
                useCase.markRead(teamId, seasonId, accessKey, principal.accountId(), notificationId)));
    }
    public record ReadNotificationRequest(@NotNull UUID expectedAccountId) {}
    public record NotificationResponse(UUID id, WorkspaceNotificationKind kind, UUID sourceId, UUID roleId,
            UUID roundId, String title, Instant occurredAt, boolean read) {}
    public record NotificationInboxResponse(UUID accountId, UUID teamId, UUID seasonId,
            List<NotificationResponse> notifications) {
        static NotificationInboxResponse from(NotificationInboxResult result) {
            return new NotificationInboxResponse(result.accountId(), result.teamId(), result.seasonId(),
                    result.notifications().stream().map(value -> new NotificationResponse(value.id(), value.kind(),
                            value.sourceId(), value.roleId(), value.roundId(), value.title(), value.occurredAt(), value.read())).toList());
        }
    }
}
