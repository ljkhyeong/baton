package com.personal.baton.application.identity;

import com.personal.baton.application.identity.port.in.IdentityInvitationAcceptanceUseCase;
import com.personal.baton.application.identity.port.in.MemberIdentityUseCase.AuthenticatedAccount;
import com.personal.baton.application.identity.port.in.MemberInvitationUseCase;
import com.personal.baton.application.identity.port.in.OwnerBootstrapInvitationUseCase;
import com.personal.baton.domain.identity.MemberIdentityRole;
import org.springframework.stereotype.Service;

@Service
public class IdentityInvitationAcceptanceService
        implements IdentityInvitationAcceptanceUseCase {

    private final OwnerBootstrapInvitationUseCase bootstrapInvitations;
    private final MemberInvitationUseCase memberInvitations;

    public IdentityInvitationAcceptanceService(
            OwnerBootstrapInvitationUseCase bootstrapInvitations,
            MemberInvitationUseCase memberInvitations
    ) {
        this.bootstrapInvitations = bootstrapInvitations;
        this.memberInvitations = memberInvitations;
    }

    @Override
    public PreviewedInvitation preview(
            String token,
            AuthenticatedAccount authenticatedAccount
    ) {
        if (isMemberInvitation(token)) {
            MemberInvitationUseCase.PreviewedMemberInvitation preview =
                    memberInvitations.preview(token, authenticatedAccount);
            return new PreviewedInvitation(
                    preview.teamId(),
                    preview.teamName(),
                    preview.memberId(),
                    preview.memberName(),
                    MemberIdentityRole.MEMBER,
                    preview.expiresAt(),
                    preview.alreadyAccepted()
            );
        }
        OwnerBootstrapInvitationUseCase.PreviewedOwnerBootstrapInvitation preview =
                bootstrapInvitations.preview(token, authenticatedAccount);
        return new PreviewedInvitation(
                preview.teamId(),
                preview.teamName(),
                preview.memberId(),
                preview.memberName(),
                MemberIdentityRole.OWNER,
                preview.expiresAt(),
                preview.alreadyAccepted()
        );
    }

    @Override
    public AcceptedInvitation accept(
            String token,
            AuthenticatedAccount authenticatedAccount
    ) {
        if (isMemberInvitation(token)) {
            MemberInvitationUseCase.AcceptedMemberInvitation accepted =
                    memberInvitations.accept(token, authenticatedAccount);
            return new AcceptedInvitation(
                    accepted.invitationId(),
                    accepted.accountId(),
                    accepted.teamId(),
                    accepted.memberId(),
                    accepted.boundAt(),
                    accepted.role()
            );
        }
        OwnerBootstrapInvitationUseCase.AcceptedOwnerBootstrapInvitation accepted =
                bootstrapInvitations.accept(token, authenticatedAccount);
        return new AcceptedInvitation(
                accepted.invitationId(),
                accepted.accountId(),
                accepted.teamId(),
                accepted.memberId(),
                accepted.boundAt(),
                accepted.role()
        );
    }

    private boolean isMemberInvitation(String token) {
        return MemberInvitationService.isMemberInvitationTokenShape(token);
    }
}
