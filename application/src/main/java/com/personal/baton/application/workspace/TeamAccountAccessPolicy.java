package com.personal.baton.application.workspace;

import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.roundauth.port.out.RoundAuthorizationRepository;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.port.out.TeamAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspacePeopleRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Member;
import com.personal.baton.domain.workspace.Team;
import com.personal.baton.domain.workspace.TeamPermission;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
final class TeamAccountAccessPolicy {
    private final CurrentAccountProvider accounts;
    private final RoundAuthorizationRepository memberships;
    private final WorkspacePeopleRepository people;
    private final TeamAccessRepository access;
    TeamAccountAccessPolicy(CurrentAccountProvider accounts, RoundAuthorizationRepository memberships,
            WorkspacePeopleRepository people, TeamAccessRepository access) {
        this.accounts = accounts; this.memberships = memberships; this.people = people; this.access = access;
    }
    AccountTeamMembership requireMembership(Team team) {
        var membership = accounts.currentAccountId().flatMap(account -> memberships.findMembership(account, team.getId()))
                .filter(value -> value.getPermission() != null).orElseThrow(WorkspaceAccessDeniedException::new);
        people.findMemberById(membership.getMemberId()).filter(member -> member.getTeamId().equals(team.getId()))
                .filter(Member::isActive).orElseThrow(WorkspaceAccessDeniedException::new);
        return membership;
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
    void requireOtherAdministrator(Team team, UUID memberId) {
        if (!team.isAccountAccessEnabled()) return;
        var memberships = access.findMemberships(team.getId());
        boolean removesAdmin = memberships.stream().anyMatch(value -> value.getMemberId().equals(memberId)
                && value.getPermission() == TeamPermission.ADMIN);
        if (!removesAdmin) return;
        var activeMembers = people.findMembersByTeamId(team.getId()).stream().filter(Member::isActive).map(Member::getId).toList();
        if (memberships.stream().noneMatch(value -> !value.getMemberId().equals(memberId)
                && value.getPermission() == TeamPermission.ADMIN && activeMembers.contains(value.getMemberId()))) {
            throw new DomainValidationException("다른 활성 관리자를 지정한 뒤 마지막 관리자의 권한이나 활동 상태를 바꿔 주세요");
        }
    }
}
