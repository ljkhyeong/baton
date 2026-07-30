package com.personal.baton.adapter.in.web.identity;

import com.personal.baton.adapter.in.web.identity.IdentityRequests.AcceptInvitationRequest;
import com.personal.baton.adapter.in.web.identity.IdentityRequests.IssueBootstrapInvitationRequest;
import com.personal.baton.adapter.in.web.identity.IdentityRequests.IssueMemberInvitationRequest;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.AcceptedInvitationResponse;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.BootstrapInvitationResponse;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.InvitationPreviewResponse;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.MeResponse;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.MemberInvitationResponse;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.OpenMemberInvitationResponse;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.RevokedMemberInvitationResponse;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.TeamMembershipResponse;
import com.personal.baton.application.identity.error.IdentityNotFoundException;
import com.personal.baton.application.identity.port.in.IdentityInvitationAcceptanceUseCase;
import com.personal.baton.application.identity.port.in.IdentityInvitationAcceptanceUseCase.AcceptedInvitation;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.IssueMemberInvitationCommand;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssueOwnerBootstrapInvitationCommand;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssuedOwnerBootstrapInvitation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {

    static final String BOOTSTRAP_KEY_HEADER =
            "X-Baton-Identity-Bootstrap-Key";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final OwnerBootstrapInvitationUseCase bootstrapInvitationUseCase;
    private final IdentityInvitationAcceptanceUseCase invitationAcceptanceUseCase;
    private final MemberIdentityUseCase memberIdentityUseCase;
    private final MemberInvitationUseCase memberInvitationUseCase;

    public IdentityController(
            OwnerBootstrapInvitationUseCase bootstrapInvitationUseCase,
            IdentityInvitationAcceptanceUseCase invitationAcceptanceUseCase,
            MemberIdentityUseCase memberIdentityUseCase,
            MemberInvitationUseCase memberInvitationUseCase
    ) {
        this.bootstrapInvitationUseCase = bootstrapInvitationUseCase;
        this.invitationAcceptanceUseCase = invitationAcceptanceUseCase;
        this.memberIdentityUseCase = memberIdentityUseCase;
        this.memberInvitationUseCase = memberInvitationUseCase;
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> getMe(
            @AuthenticationPrincipal BatonAccountPrincipal principal
    ) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(MeResponse.from(principal));
    }

    @PostMapping("/identity/bootstrap-invitations")
    public ResponseEntity<BootstrapInvitationResponse> issueBootstrapInvitation(
            @RequestHeader(name = BOOTSTRAP_KEY_HEADER, required = false)
            String operatorBootstrapKey,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
            String idempotencyKey,
            @Valid @RequestBody IssueBootstrapInvitationRequest request
    ) {
        IssuedOwnerBootstrapInvitation invitation = bootstrapInvitationUseCase.issue(
                operatorBootstrapKey,
                idempotencyKey,
                new IssueOwnerBootstrapInvitationCommand(
                        request.teamId(),
                        request.memberId()
                )
        );
        HttpStatus status = invitation.replayed()
                ? HttpStatus.OK
                : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(BootstrapInvitationResponse.from(invitation));
    }

    @PostMapping("/identity/invitations/preview")
    public ResponseEntity<InvitationPreviewResponse> previewInvitation(
            @AuthenticationPrincipal BatonAccountPrincipal principal,
            @Valid @RequestBody AcceptInvitationRequest request
    ) {
        var preview = invitationAcceptanceUseCase.preview(
                request.token(),
                authenticatedAccount(principal)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(InvitationPreviewResponse.from(preview));
    }

    @PostMapping("/identity/invitations/accept")
    public ResponseEntity<AcceptedInvitationResponse> acceptInvitation(
            @AuthenticationPrincipal BatonAccountPrincipal principal,
            @Valid @RequestBody AcceptInvitationRequest request
    ) {
        AcceptedInvitation invitation = invitationAcceptanceUseCase.accept(
                request.token(),
                authenticatedAccount(principal)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AcceptedInvitationResponse.from(invitation));
    }

    @GetMapping("/teams/{teamId}/membership")
    public ResponseEntity<TeamMembershipResponse> getTeamMembership(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal BatonAccountPrincipal principal
    ) {
        var membership = memberIdentityUseCase.findActiveMember(
                        teamId,
                        authenticatedAccount(principal)
                )
                .orElseThrow(() -> new IdentityNotFoundException(
                        "MEMBERSHIP_NOT_FOUND",
                        "활성 팀 구성원 결속을 찾을 수 없습니다"
                ));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(TeamMembershipResponse.from(membership));
    }

    @PostMapping("/teams/{teamId}/member-invitations")
    public ResponseEntity<MemberInvitationResponse> issueMemberInvitation(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal BatonAccountPrincipal principal,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false)
            String idempotencyKey,
            @Valid @RequestBody IssueMemberInvitationRequest request
    ) {
        var invitation = memberInvitationUseCase.issue(
                authenticatedAccount(principal),
                idempotencyKey,
                new IssueMemberInvitationCommand(teamId, request.memberId())
        );
        HttpStatus status = invitation.replayed()
                ? HttpStatus.OK
                : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(MemberInvitationResponse.from(invitation));
    }

    @GetMapping("/teams/{teamId}/member-invitations")
    public ResponseEntity<List<OpenMemberInvitationResponse>> listMemberInvitations(
            @PathVariable UUID teamId,
            @AuthenticationPrincipal BatonAccountPrincipal principal
    ) {
        List<OpenMemberInvitationResponse> invitations = memberInvitationUseCase.listOpen(
                        teamId,
                        authenticatedAccount(principal)
                )
                .stream()
                .map(OpenMemberInvitationResponse::from)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(invitations);
    }

    @PostMapping("/teams/{teamId}/member-invitations/{invitationId}/revocation")
    public ResponseEntity<RevokedMemberInvitationResponse> revokeMemberInvitation(
            @PathVariable UUID teamId,
            @PathVariable UUID invitationId,
            @AuthenticationPrincipal BatonAccountPrincipal principal
    ) {
        var invitation = memberInvitationUseCase.revoke(
                teamId,
                invitationId,
                authenticatedAccount(principal)
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(RevokedMemberInvitationResponse.from(invitation));
    }

    private AuthenticatedAccount authenticatedAccount(
            BatonAccountPrincipal principal
    ) {
        return new AuthenticatedAccount(principal.accountId());
    }
}
