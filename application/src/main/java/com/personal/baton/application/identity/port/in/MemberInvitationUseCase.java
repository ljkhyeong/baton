package com.personal.baton.application.identity.port.in;

import com.personal.baton.domain.identity.MemberIdentityRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public interface MemberInvitationUseCase {

    IssuedMemberInvitation issue(
            MemberIdentityUseCase.AuthenticatedAccount authenticatedAccount,
            String idempotencyKey,
            IssueMemberInvitationCommand command
    );

    List<OpenMemberInvitation> listOpen(
            UUID teamId,
            MemberIdentityUseCase.AuthenticatedAccount authenticatedAccount
    );

    PreviewedMemberInvitation preview(
            String token,
            MemberIdentityUseCase.AuthenticatedAccount authenticatedAccount
    );

    AcceptedMemberInvitation accept(
            String token,
            MemberIdentityUseCase.AuthenticatedAccount authenticatedAccount
    );

    RevokedMemberInvitation revoke(
            UUID teamId,
            UUID invitationId,
            MemberIdentityUseCase.AuthenticatedAccount authenticatedAccount
    );

    record IssueMemberInvitationCommand(UUID teamId, UUID memberId) {

        public IssueMemberInvitationCommand {
            Objects.requireNonNull(teamId, "구성원 초대 팀 식별자는 필수입니다");
            Objects.requireNonNull(memberId, "구성원 초대 대상 식별자는 필수입니다");
        }
    }

    record IssuedMemberInvitation(
            UUID invitationId,
            UUID teamId,
            UUID memberId,
            String token,
            Instant issuedAt,
            Instant expiresAt,
            boolean replayed
    ) {
    }

    record OpenMemberInvitation(
            UUID invitationId,
            UUID teamId,
            UUID memberId,
            Instant issuedAt,
            Instant expiresAt
    ) {
    }

    record PreviewedMemberInvitation(
            UUID teamId,
            String teamName,
            UUID memberId,
            String memberName,
            Instant expiresAt,
            boolean alreadyAccepted
    ) {
    }

    record AcceptedMemberInvitation(
            UUID invitationId,
            UUID accountId,
            UUID teamId,
            UUID memberId,
            Instant boundAt,
            MemberIdentityRole role
    ) {
    }

    record RevokedMemberInvitation(UUID invitationId, Instant revokedAt) {
    }
}
