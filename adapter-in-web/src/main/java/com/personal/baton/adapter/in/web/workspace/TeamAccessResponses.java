package com.personal.baton.adapter.in.web.workspace;

import com.personal.baton.domain.workspace.TeamPermission;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase.MyTeamsResult;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase.TeamAccessResult;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase.InvitationResult;

public final class TeamAccessResponses {
    private TeamAccessResponses() {}

    public record MyTeamResponse(UUID teamId, String teamName, UUID memberId, String memberName,
            TeamPermission permission, UUID seasonId, String seasonName, boolean seasonEnded) {}
    public record MyTeamsResponse(UUID accountId, List<MyTeamResponse> teams) {
        static MyTeamsResponse from(MyTeamsResult value) {
            return new MyTeamsResponse(value.accountId(), value.teams().stream().map(team -> new MyTeamResponse(
                    team.teamId(), team.teamName(), team.memberId(), team.memberName(), team.permission(),
                    team.seasonId(), team.seasonName(), team.seasonEnded())).toList());
        }
    }
    public record TeamInvitationResponse(UUID id, UUID memberId, TeamPermission permission, Instant createdAt,
            Instant expiresAt, Instant acceptedAt, Instant revokedAt) {
        static TeamInvitationResponse from(InvitationResult value) { return new TeamInvitationResponse(value.id(), value.memberId(),
                value.permission(), value.createdAt(), value.expiresAt(), value.acceptedAt(), value.revokedAt()); }
    }
    public record CreatedTeamInvitationResponse(TeamInvitationResponse invitation, String token) {
        @Override public String toString() { return "CreatedTeamInvitationResponse[invitation=" + invitation.id() + "]"; }
    }
    public record TeamInvitationPreviewResponse(UUID teamId, String teamName, UUID memberId, String memberName,
            TeamPermission permission, Instant expiresAt) {}
    public record TeamInvitationAcceptedResponse(UUID accountId, UUID teamId, UUID seasonId, UUID memberId, TeamPermission permission) {}
    public record TeamMemberAccessResponse(UUID memberId, String memberName, boolean active, UUID accountId, TeamPermission permission) {}
    public record TeamAccessAuditResponse(UUID id, UUID actorAccountId, UUID memberId, String action,
            TeamPermission previousPermission, TeamPermission permission, Instant changedAt) {}
    public record TeamAccessResponse(UUID teamId, UUID accountId, boolean accountAccessEnabled, UUID memberId,
            TeamPermission permission, List<TeamMemberAccessResponse> members, List<TeamInvitationResponse> invitations,
            List<TeamAccessAuditResponse> audit) {
        static TeamAccessResponse from(TeamAccessResult value) {
            return new TeamAccessResponse(value.teamId(), value.accountId(), value.accountAccessEnabled(), value.memberId(), value.permission(),
                    value.members().stream().map(member -> new TeamMemberAccessResponse(member.memberId(), member.memberName(), member.active(), member.accountId(), member.permission())).toList(),
                    value.invitations().stream().map(TeamInvitationResponse::from).toList(),
                    value.audit().stream().map(audit -> new TeamAccessAuditResponse(audit.id(), audit.actorAccountId(), audit.memberId(), audit.action(), audit.previousPermission(), audit.permission(), audit.changedAt())).toList());
        }
    }
}
