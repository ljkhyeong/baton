package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.adapter.in.web.auth.AuthenticatedAccountPrincipal;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.ActivateTeamAccessRequest;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.ExpectedAccountRequest;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.CreateTeamInvitationRequest;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.ChangeTeamPermissionRequest;
import com.personal.baton.adapter.in.web.workspace.TeamAccessRequests.TeamInvitationTokenRequest;
import com.personal.baton.adapter.in.web.workspace.TeamAccessResponses.TeamAccessResponse;
import com.personal.baton.adapter.in.web.workspace.TeamAccessResponses.CreatedTeamInvitationResponse;
import com.personal.baton.adapter.in.web.workspace.TeamAccessResponses.TeamInvitationResponse;
import com.personal.baton.adapter.in.web.workspace.TeamAccessResponses.TeamInvitationPreviewResponse;
import com.personal.baton.adapter.in.web.workspace.TeamAccessResponses.TeamInvitationAcceptedResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TeamAccessController {
    public static final String PATH = "/api/v1/team-access/{teamId}";
    public static final String INVITATION_PATH = "/api/v1/team-invitations";
    private final TeamAccessUseCase useCase;
    public TeamAccessController(TeamAccessUseCase useCase) { this.useCase = useCase; }
    @GetMapping(PATH)
    public ResponseEntity<TeamAccessResponse> get(@PathVariable UUID teamId,
            @RequestHeader(value = "X-Baton-Access-Key", required = false) String accessKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal) {
        return ok(TeamAccessResponse.from(useCase.getAccess(teamId, principal.accountId(), accessKey)));
    }
    @PostMapping(PATH + "/activate")
    public ResponseEntity<TeamAccessResponse> activate(@PathVariable UUID teamId,
            @RequestHeader("X-Baton-Recovery-Key") String recoveryKey,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody ActivateTeamAccessRequest request) {
        expected(principal, request.expectedAccountId());
        return ok(TeamAccessResponse.from(useCase.activate(teamId, principal.accountId(), request.memberId(), recoveryKey)));
    }
    @PostMapping(PATH + "/invitations")
    public ResponseEntity<CreatedTeamInvitationResponse> invite(@PathVariable UUID teamId,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody CreateTeamInvitationRequest request) {
        expected(principal, request.expectedAccountId());
        var created = useCase.invite(teamId, principal.accountId(), request.memberId(), request.permission());
        return ok(new CreatedTeamInvitationResponse(TeamInvitationResponse.from(created.invitation()), created.token()));
    }
    @PostMapping(PATH + "/invitations/{invitationId}/revoke")
    public ResponseEntity<TeamAccessResponse> revoke(@PathVariable UUID teamId, @PathVariable UUID invitationId,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody ExpectedAccountRequest request) {
        expected(principal, request.expectedAccountId());
        return ok(TeamAccessResponse.from(useCase.revokeInvitation(teamId, principal.accountId(), invitationId)));
    }
    @PutMapping(PATH + "/members/{memberId}/permission")
    public ResponseEntity<TeamAccessResponse> permission(@PathVariable UUID teamId, @PathVariable UUID memberId,
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody ChangeTeamPermissionRequest request) {
        expected(principal, request.expectedAccountId());
        return ok(TeamAccessResponse.from(useCase.changePermission(teamId, principal.accountId(), memberId, request.permission())));
    }
    @PostMapping(INVITATION_PATH + "/preview")
    public ResponseEntity<TeamInvitationPreviewResponse> preview(
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody TeamInvitationTokenRequest request) {
        expected(principal, request.expectedAccountId());
        var value = useCase.preview(principal.accountId(), request.token());
        return ok(new TeamInvitationPreviewResponse(value.teamId(), value.teamName(), value.memberId(), value.memberName(),
                value.permission(), value.expiresAt()));
    }
    @PostMapping(INVITATION_PATH + "/accept")
    public ResponseEntity<TeamInvitationAcceptedResponse> accept(
            @AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedAccountPrincipal principal,
            @Valid @RequestBody TeamInvitationTokenRequest request) {
        expected(principal, request.expectedAccountId());
        var value = useCase.accept(principal.accountId(), request.token());
        return ok(new TeamInvitationAcceptedResponse(value.accountId(), value.teamId(), value.seasonId(), value.memberId(), value.permission()));
    }
    private void expected(AuthenticatedAccountPrincipal principal, UUID accountId) {
        if (!principal.accountId().equals(accountId)) throw new AccountMembershipConflictException("로그인 계정이 변경되었습니다. 새로고침해 주세요.");
    }
    private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }

}
