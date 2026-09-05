package com.personal.baton.application.workspace;

import com.personal.baton.application.calendar.port.out.CalendarSubscriptionStore;
import com.personal.baton.application.identity.error.AccountDeactivationBlockedException;
import com.personal.baton.application.identity.error.AccountNotFoundException;
import com.personal.baton.application.identity.port.in.DeactivateAccountUseCase;
import com.personal.baton.application.identity.port.out.CurrentAccountProvider;
import com.personal.baton.application.identity.port.out.IdentityRepository;
import com.personal.baton.application.workspace.error.WorkspaceAccessDeniedException;
import com.personal.baton.application.workspace.error.WorkspaceContentConflictException;
import com.personal.baton.application.workspace.port.out.TeamAccessRepository;
import com.personal.baton.application.workspace.port.out.WorkspaceAccessRepository;
import com.personal.baton.domain.workspace.DomainValidationException;
import com.personal.baton.domain.workspace.Team;
import com.personal.baton.domain.workspace.TeamAccessAudit;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class AccountDeactivationService implements DeactivateAccountUseCase {
    private final IdentityRepository identities;
    private final CurrentAccountProvider currentAccount;
    private final TeamAccessRepository access;
    private final WorkspaceAccessRepository teams;
    private final TeamAccountAccessPolicy policy;
    private final CalendarSubscriptionStore calendars;
    private final Clock clock;

    public AccountDeactivationService(IdentityRepository identities, CurrentAccountProvider currentAccount,
            TeamAccessRepository access, WorkspaceAccessRepository teams, TeamAccountAccessPolicy policy,
            CalendarSubscriptionStore calendars, Clock clock) {
        this.identities = identities; this.currentAccount = currentAccount; this.access = access;
        this.teams = teams; this.policy = policy; this.calendars = calendars; this.clock = clock;
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void deactivateAccount(UUID accountId) {
        if (currentAccount.currentAccountId().filter(accountId::equals).isEmpty()) throw new WorkspaceAccessDeniedException();
        var lockedTeams = new LinkedHashMap<UUID, Team>();
        var teamIds = access.findAccountMembershipTeamIds(accountId).stream().sorted().toList();
        for (UUID teamId : teamIds) {
            lockedTeams.put(teamId, teams.findTeamByIdForUpdate(teamId).orElseThrow());
        }
        var account = identities.findAccountByIdForUpdate(accountId).orElseThrow(AccountNotFoundException::new);
        if (!account.isActive()) return;
        // 팀 잠금을 기다리는 동안 가입이 완료되었으면 현재 행을 확인하고 전체 작업을 재시도한다.
        var memberships = access.lockAccountMemberships(accountId);
        if (memberships.stream().anyMatch(value -> !lockedTeams.containsKey(value.getTeamId()))) {
            throw new WorkspaceContentConflictException();
        }
        for (var membership : memberships) {
            Team team = lockedTeams.get(membership.getTeamId());
            try {
                policy.requireOtherAdministrator(team, membership.getMemberId());
            } catch (DomainValidationException exception) {
                throw new AccountDeactivationBlockedException(team.getName());
            }
        }
        var now = clock.instant();
        for (var membership : memberships) {
            var previous = membership.getPermission();
            if (previous == null) continue;
            membership.changePermission(null);
            access.saveMembership(membership);
            access.saveAudit(TeamAccessAudit.create(membership.getTeamId(), accountId, membership.getMemberId(),
                    "ACCOUNT_DEACTIVATED", previous, null, now));
        }
        account.deactivate(now);
        identities.saveAccount(account);
        calendars.requestAccountRevocation(accountId);
    }
}
