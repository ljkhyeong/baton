package com.personal.baton.adapter.in.web.identity;

import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.AcceptedOwnerBootstrapInvitation;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase.IssuedOwnerBootstrapInvitation;
import com.personal.baton.domain.identity.MemberIdentityRole;
import java.time.Instant;
import java.util.UUID;

public final class IdentityResponses {

    private IdentityResponses() {
    }

    public record MeResponse(UUID accountId) {

        static MeResponse from(BatonAccountPrincipal principal) {
            return new MeResponse(principal.accountId());
        }
    }

    public record BootstrapInvitationResponse(
            UUID invitationId,
            UUID teamId,
            UUID memberId,
            String token,
            Instant issuedAt,
            Instant expiresAt
    ) {

        static BootstrapInvitationResponse from(
                IssuedOwnerBootstrapInvitation invitation
        ) {
            return new BootstrapInvitationResponse(
                    invitation.invitationId(),
                    invitation.teamId(),
                    invitation.memberId(),
                    invitation.token(),
                    invitation.issuedAt(),
                    invitation.expiresAt()
            );
        }
    }

    public record AcceptedInvitationResponse(
            UUID invitationId,
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant boundAt,
            MemberIdentityRole role
    ) {

        static AcceptedInvitationResponse from(
                AcceptedOwnerBootstrapInvitation invitation
        ) {
            return new AcceptedInvitationResponse(
                    invitation.invitationId(),
                    invitation.accountId(),
                    invitation.teamId(),
                    invitation.memberId(),
                    invitation.boundAt(),
                    invitation.role()
            );
        }
    }
}
