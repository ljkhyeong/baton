package com.personal.baton.application.workspace;

import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.roundauth.ActiveAccountTeamMembershipVerifier;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.port.out.TeamAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Team;
import com.personal.baton.domain.workspace.TeamPermission;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class TeamAccountAccessPolicy {
    private final CurrentAccountProvider accounts;
    private final RoundAuthorizationRepository memberships;
    private final WorkspacePeopleRepository people;
    private final TeamAccessRepository access;
    private final ActiveAccountTeamMembershipVerifier activeMemberships;
    TeamAccountAccessPolicy(CurrentAccountProvider accounts, RoundAuthorizationRepository memberships,
            WorkspacePeopleRepository people, TeamAccessRepository access,
            ActiveAccountTeamMembershipVerifier activeMemberships) {
        this.accounts = accounts; this.memberships = memberships; this.people = people; this.access = access;
        this.activeMemberships = activeMemberships;
    }
    AccountTeamMembership requireMembership(Team team) {
        var membership = accounts.currentAccountId().flatMap(account -> memberships.findMembership(account, team.getId()))
                .filter(value -> value.getPermission() != null).orElseThrow(WorkspaceAccessDeniedException::new);
        people.findMemberById(membership.getMemberId()).filter(member -> member.getTeamId().equals(team.getId()))
                .filter(Member::isActive).orElseThrow(WorkspaceAccessDeniedException::new);
        return membership;
    }
    Member requireActiveAccountMember(UUID teamId, UUID accountId) {
        return memberships.findMembership(accountId, teamId)
                .flatMap(membership -> people.findMemberById(membership.getMemberId()))
                .filter(member -> member.getTeamId().equals(teamId)).filter(Member::isActive)
                .orElseThrow(WorkspaceAccessDeniedException::new);
    }
    void requireRead(Team team) { requireMembership(team); }
    void requireWrite(Team team) {
        if (requireMembership(team).getPermission() == TeamPermission.VIEWER) throw new WorkspaceAccessDeniedException();
    }
    void requireAdministrator(Team team) {
        if (team.isAccountAccessEnabled() && requireMembership(team).getPermission() != TeamPermission.ADMIN)
            throw new WorkspaceAccessDeniedException();
    }
    void requireConfirmedMember(Team team, UUID memberId) {
        if (team.isAccountAccessEnabled() && !requireMembership(team).getMemberId().equals(memberId))
            throw new WorkspaceAccessDeniedException();
    }
    // 이전 담당자가 직접 취소할 수 없을 때만 관리자가 대신한다. 대신한 관리자 계정을 돌려주고 본인 명의면 비워 둔다.
    Optional<UUID> requireHandoffCancelConfirmer(Team team, UUID memberId) {
        if (!team.isAccountAccessEnabled()) return Optional.empty();
        AccountTeamMembership actor = requireMembership(team);
        if (actor.getMemberId().equals(memberId)) return Optional.empty();
        if (actor.getPermission() != TeamPermission.ADMIN || canConfirmHandoff(team.getId(), memberId))
            throw new WorkspaceAccessDeniedException();
        return Optional.of(actor.getAccountId());
    }
    private boolean canConfirmHandoff(UUID teamId, UUID memberId) {
        return access.findMembershipByMemberId(memberId)
                .filter(value -> value.getTeamId().equals(teamId))
                .filter(value -> value.getPermission() != null && value.getPermission() != TeamPermission.VIEWER)
                .filter(value -> activeMemberships.hasActiveMembership(value.getAccountId(), teamId))
                .isPresent();
    }
    void requireOtherAdministrator(Team team, UUID memberId) {
        if (!team.isAccountAccessEnabled()) return;
        boolean removesAdmin = access.findMembershipByMemberId(memberId)
                .filter(value -> value.getTeamId().equals(team.getId()))
                .filter(value -> value.getPermission() == TeamPermission.ADMIN).isPresent();
        if (!removesAdmin) return;
        if (!access.existsOtherActiveAdministrator(team.getId(), memberId)) {
            throw new DomainValidationException("다른 활성 관리자를 지정한 뒤 마지막 관리자의 권한이나 활동 상태를 바꿔 주세요");
        }
    }
}
