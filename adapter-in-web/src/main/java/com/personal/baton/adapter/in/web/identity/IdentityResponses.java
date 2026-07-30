package com.personal.baton.adapter.in.web.identity;

import com.personal.baton.application.identity.port.in.IdentityInvitationAcceptanceUseCase.AcceptedInvitation;
import com.personal.baton.application.identity.port.in.IdentityInvitationAcceptanceUseCase.PreviewedInvitation;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.MemberIdentityResult;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.IssuedMemberInvitation;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.OpenMemberInvitation;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase.RevokedMemberInvitation;
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
                AcceptedInvitation invitation
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

    public record InvitationPreviewResponse(
            UUID teamId,
            String teamName,
            UUID memberId,
            String memberName,
            MemberIdentityRole role,
            Instant expiresAt,
            boolean alreadyAccepted
    ) {

        static InvitationPreviewResponse from(PreviewedInvitation invitation) {
            return new InvitationPreviewResponse(
                    invitation.teamId(),
                    invitation.teamName(),
                    invitation.memberId(),
                    invitation.memberName(),
                    invitation.role(),
                    invitation.expiresAt(),
                    invitation.alreadyAccepted()
            );
        }
    }

    public record TeamMembershipResponse(
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant boundAt,
            MemberIdentityRole role
    ) {

        static TeamMembershipResponse from(MemberIdentityResult membership) {
            return new TeamMembershipResponse(
                    membership.accountId(),
                    membership.teamId(),
                    membership.memberId(),
                    membership.boundAt(),
                    membership.role()
            );
        }
    }

    public record MemberInvitationResponse(
            UUID invitationId,
            UUID teamId,
            UUID memberId,
            String token,
            Instant issuedAt,
            Instant expiresAt
    ) {

        static MemberInvitationResponse from(IssuedMemberInvitation invitation) {
            return new MemberInvitationResponse(
                    invitation.invitationId(),
                    invitation.teamId(),
                    invitation.memberId(),
                    invitation.token(),
                    invitation.issuedAt(),
                    invitation.expiresAt()
            );
        }
    }

    public record OpenMemberInvitationResponse(
            UUID invitationId,
            UUID teamId,
            UUID memberId,
            Instant issuedAt,
            Instant expiresAt
    ) {

        static OpenMemberInvitationResponse from(OpenMemberInvitation invitation) {
            return new OpenMemberInvitationResponse(
                    invitation.invitationId(),
                    invitation.teamId(),
                    invitation.memberId(),
                    invitation.issuedAt(),
                    invitation.expiresAt()
            );
        }
    }

    public record RevokedMemberInvitationResponse(
            UUID invitationId,
            Instant revokedAt
    ) {

        static RevokedMemberInvitationResponse from(
                RevokedMemberInvitation invitation
        ) {
            return new RevokedMemberInvitationResponse(
                    invitation.invitationId(),
                    invitation.revokedAt()
            );
        }
    }
}
