package com.personal.baton.adapter.out.persistence.workspace;

import static com.personal.baton.adapter.out.persistence.PersistenceConstraintViolations.hasConstraint;

import com.personal.baton.adapter.out.persistence.roundauth.AccountTeamMembershipJpaRepository;
import com.personal.baton.application.roundauth.error.AccountMembershipConflictException;
import com.personal.baton.application.workspace.port.out.TeamAccessRepository;
import com.personal.baton.domain.roundauth.AccountTeamMembership;
import com.personal.baton.domain.workspace.TeamInvitation;
import com.personal.baton.domain.workspace.TeamAccessAudit;
import java.util.List;
import java.util.LinkedHashMap;
import com.personal.baton.application.workspace.port.in.TeamAccessUseCase.MyTeamResult;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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
    @Override public Optional<AccountTeamMembership> findMembershipByMemberId(UUID memberId) { return memberships.findByMemberId(memberId); }
    @Override public boolean existsOtherActiveAdministrator(UUID teamId, UUID memberId) {
        return memberships.existsOtherActiveAdministrator(teamId, memberId);
    }
    @Override public AccountTeamMembership saveMembership(AccountTeamMembership value) {
        try {
            return memberships.saveAndFlush(value);
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(exception, "uk_account_team_memberships_account_team")
                    || hasConstraint(exception, "uk_account_team_memberships_member")) {
                throw new AccountMembershipConflictException("이미 다른 계정 또는 구성원으로 연결되어 있습니다", exception);
            }
            throw exception;
        }
    }
    @Override public Optional<UUID> findInvitationTeamId(String tokenHash) { return invitations.findTeamIdByTokenHash(tokenHash); }
    @Override public Optional<TeamInvitation> lockInvitation(String tokenHash) { return invitations.lockByTokenHash(tokenHash); }
    @Override public Optional<TeamInvitation> findInvitationByTokenHash(String tokenHash) { return invitations.findByTokenHash(tokenHash); }
    @Override public Optional<TeamInvitation> findInvitation(UUID id) { return invitations.findById(id); }
    @Override public List<TeamInvitation> findInvitations(UUID teamId) { return invitations.findAllByTeamIdOrderByCreatedAtDesc(teamId); }
    @Override public List<TeamInvitation> findUnacceptedInvitations(UUID teamId, UUID memberId) {
        return invitations.findAllByTeamIdAndMemberIdAndAcceptedAtIsNullAndRevokedAtIsNull(teamId, memberId);
    }
    @Override public TeamInvitation saveInvitation(TeamInvitation invitation) { return invitations.save(invitation); }
    @Override public void saveAudit(TeamAccessAudit audit) { audits.save(audit); }
    @Override public List<TeamAccessAudit> findAudit(UUID teamId) { return audits.findTop50ByTeamIdOrderByChangedAtDescIdDesc(teamId); }
}
