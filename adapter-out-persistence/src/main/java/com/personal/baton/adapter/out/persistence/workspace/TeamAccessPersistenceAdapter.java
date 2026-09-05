package com.personal.baton.adapter.out.persistence.workspace;

import com.personal.baton.adapter.out.persistence.roundauth.AccountTeamMembershipJpaRepository;
import com.personal.baton.application.workspace.port.out.TeamAccessRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.TeamInvitation;
import com.personal.baton.domain.workspace.TeamAccessAudit;
import java.util.List;
import java.util.LinkedHashMap;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase.MyTeamResult;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class TeamAccessPersistenceAdapter implements TeamAccessRepository {
    private final AccountTeamMembershipJpaRepository memberships;
    private final TeamInvitationJpaRepository invitations;
    private final TeamAccessAuditJpaRepository audits;
    public TeamAccessPersistenceAdapter(AccountTeamMembershipJpaRepository memberships,
            TeamInvitationJpaRepository invitations, TeamAccessAuditJpaRepository audits) {
        this.memberships = memberships; this.invitations = invitations; this.audits = audits;
    }
    @Override
    public List<MyTeamResult> findAccountTeams(UUID accountId) {
        var teams = new LinkedHashMap<UUID, MyTeamResult>();
        for (var row : memberships.findAccountTeamSeasons(accountId)) {
            teams.putIfAbsent(row.getTeamId(), new MyTeamResult(row.getTeamId(), row.getTeamName(), row.getMemberId(),
                    row.getMemberName(), row.getPermission(), row.getSeasonId(), row.getSeasonName(), row.getSeasonEnded()));
        }
        return List.copyOf(teams.values());
    }
    @Override public List<UUID> findAccountMembershipTeamIds(UUID accountId) { return memberships.findTeamIdsByAccountId(accountId); }
    @Override public List<AccountTeamMembership> lockAccountMemberships(UUID accountId) { return memberships.lockByAccountId(accountId); }
    @Override public List<AccountTeamMembership> findMemberships(UUID teamId) { return memberships.findAllByTeamId(teamId); }
    @Override public AccountTeamMembership saveMembership(AccountTeamMembership value) { return memberships.save(value); }
    @Override public Optional<UUID> findInvitationTeamId(String tokenHash) { return invitations.findTeamIdByTokenHash(tokenHash); }
    @Override public Optional<TeamInvitation> lockInvitation(String tokenHash) { return invitations.lockByTokenHash(tokenHash); }
    @Override public Optional<TeamInvitation> findInvitationByTokenHash(String tokenHash) { return invitations.findByTokenHash(tokenHash); }
    @Override public Optional<TeamInvitation> findInvitation(UUID id) { return invitations.findById(id); }
    @Override public List<TeamInvitation> findInvitations(UUID teamId) { return invitations.findAllByTeamIdOrderByCreatedAtDesc(teamId); }
    @Override public TeamInvitation saveInvitation(TeamInvitation invitation) { return invitations.save(invitation); }
    @Override public void saveAudit(TeamAccessAudit audit) { audits.save(audit); }
    @Override public List<TeamAccessAudit> findAudit(UUID teamId) { return audits.findTop50ByTeamIdOrderByChangedAtDescIdDesc(teamId); }
}
