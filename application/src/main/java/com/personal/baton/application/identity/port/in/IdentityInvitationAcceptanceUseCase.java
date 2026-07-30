package com.personal.baton.application.identity.port.in;

import com.personal.baton.domain.identity.MemberIdentityRole;
import java.time.Instant;
import java.util.UUID;

public interface IdentityInvitationAcceptanceUseCase {

    PreviewedInvitation preview(
            String token,
            MemberIdentityUseCase.AuthenticatedAccount authenticatedAccount
    );

    AcceptedInvitation accept(
            String token,
            MemberIdentityUseCase.AuthenticatedAccount authenticatedAccount
    );

    record PreviewedInvitation(
            UUID teamId,
            String teamName,
            UUID memberId,
            String memberName,
            MemberIdentityRole role,
            Instant expiresAt,
            boolean alreadyAccepted
    ) {
    }

    record AcceptedInvitation(
            UUID invitationId,
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant boundAt,
            MemberIdentityRole role
    ) {
    }
}
