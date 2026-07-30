package com.personal.baton.adapter.in.web.identity;

import com.personal.baton.adapter.in.web.identity.IdentityRequests.AcceptInvitationRequest;
import com.personal.baton.adapter.in.web.identity.IdentityRequests.IssueBootstrapInvitationRequest;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.AcceptedInvitationResponse;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.BootstrapInvitationResponse;
import com.personal.baton.adapter.in.web.identity.IdentityResponses.MeResponse;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.AcceptedOwnerBootstrapInvitation;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssueOwnerBootstrapInvitationCommand;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssuedOwnerBootstrapInvitation;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final OwnerBootstrapInvitationUseCase invitationUseCase;

    public IdentityController(
            OwnerBootstrapInvitationUseCase invitationUseCase
    ) {
        this.invitationUseCase = invitationUseCase;
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
        IssuedOwnerBootstrapInvitation invitation = invitationUseCase.issue(
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

    @PostMapping("/identity/invitations/accept")
    public ResponseEntity<AcceptedInvitationResponse> acceptInvitation(
            @AuthenticationPrincipal BatonAccountPrincipal principal,
            @Valid @RequestBody AcceptInvitationRequest request
    ) {
        AcceptedOwnerBootstrapInvitation invitation = invitationUseCase.accept(
                request.token(),
                new AuthenticatedAccount(principal.accountId())
        );
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(AcceptedInvitationResponse.from(invitation));
    }
}
