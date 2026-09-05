package com.personal.baton.application.workspace.port.in;

import com.personal.baton.domain.workspace.TeamPermission;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TeamAccessUseCase {
    MyTeamsResult getMyTeams(UUID accountId);
    record MyTeamsResult(UUID accountId, List<MyTeamResult> teams) {}
    record MyTeamResult(UUID teamId, String teamName, UUID memberId, String memberName,
            TeamPermission permission, UUID seasonId, String seasonName, boolean seasonEnded) {}

    TeamAccessResult getAccess(UUID teamId, UUID accountId, String accessKey);
    TeamAccessResult activate(UUID teamId, UUID accountId, UUID memberId, String recoveryKey);
    CreatedInvitationResult invite(UUID teamId, UUID accountId, UUID memberId, TeamPermission permission);
    TeamAccessResult revokeInvitation(UUID teamId, UUID accountId, UUID invitationId);
    TeamAccessResult changePermission(UUID teamId, UUID accountId, UUID memberId, TeamPermission permission);
    InvitationPreviewResult preview(UUID accountId, String token);
    InvitationAcceptedResult accept(UUID accountId, String token);

    record MemberAccessResult(UUID memberId, String memberName, boolean active, UUID accountId, TeamPermission permission) {}
    record InvitationResult(UUID id, UUID memberId, TeamPermission permission, Instant createdAt, Instant expiresAt,
            Instant acceptedAt, Instant revokedAt) {}
    record AccessAuditResult(UUID id, UUID actorAccountId, UUID memberId, String action,
            TeamPermission previousPermission, TeamPermission permission, Instant changedAt) {}
    record TeamAccessResult(UUID teamId, UUID accountId, boolean accountAccessEnabled, UUID memberId,
            TeamPermission permission, List<MemberAccessResult> members, List<InvitationResult> invitations,
            List<AccessAuditResult> audit) {}
    record CreatedInvitationResult(InvitationResult invitation, String token) {
        @Override public String toString() { return "CreatedInvitationResult[invitation=" + invitation.id() + "]"; }
    }
    record InvitationPreviewResult(UUID teamId, String teamName, UUID memberId, String memberName,
            TeamPermission permission, Instant expiresAt) {}
    record InvitationAcceptedResult(UUID accountId, UUID teamId, UUID seasonId, UUID memberId, TeamPermission permission) {}
}
