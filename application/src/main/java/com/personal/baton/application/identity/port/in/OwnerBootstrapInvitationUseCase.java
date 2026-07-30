package com.personal.baton.application.identity.port.in;

import com.personal.baton.domain.identity.MemberIdentityRole;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public interface OwnerBootstrapInvitationUseCase {

    IssuedOwnerBootstrapInvitation issue(
            String operatorBootstrapKey,
            String idempotencyKey,
            IssueOwnerBootstrapInvitationCommand command
    );

    AcceptedOwnerBootstrapInvitation accept(
            String token,
            MemberIdentityUseCase.AuthenticatedAccount authenticatedAccount
    );

    PreviewedOwnerBootstrapInvitation preview(
            String token,
            MemberIdentityUseCase.AuthenticatedAccount authenticatedAccount
    );

    RevokedOwnerBootstrapInvitation revoke(
            String operatorBootstrapKey,
            UUID invitationId
    );

    record IssueOwnerBootstrapInvitationCommand(UUID teamId, UUID memberId) {

        public IssueOwnerBootstrapInvitationCommand {
            Objects.requireNonNull(teamId, "bootstrap 초대 팀 식별자는 필수입니다");
            Objects.requireNonNull(memberId, "bootstrap 초대 구성원 식별자는 필수입니다");
        }
    }

    record IssuedOwnerBootstrapInvitation(
            UUID invitationId,
            UUID teamId,
            UUID memberId,
            String token,
            Instant issuedAt,
            Instant expiresAt,
            boolean replayed
    ) {
    }

    record AcceptedOwnerBootstrapInvitation(
            UUID invitationId,
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant boundAt,
            MemberIdentityRole role
    ) {
    }

    record PreviewedOwnerBootstrapInvitation(
            UUID teamId,
            String teamName,
            UUID memberId,
            String memberName,
            Instant expiresAt,
            boolean alreadyAccepted
    ) {
    }

    record RevokedOwnerBootstrapInvitation(
            UUID invitationId,
            Instant revokedAt
    ) {
    }
}
